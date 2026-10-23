package app.bookorbit.community.data

import app.bookorbit.community.reader.*
import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotebookStorageTest {
    private val context
        get() = RuntimeEnvironment.getApplication()

    private val owner = "server-account"
    private val file = EbookFile(101, "epub", "book.epub", fileHash = "revision")
    private val book = SavedEbook(1, "A journey", listOf("Author Smith"), file)
    private val note =
        ReadingNote(
            id = "note",
            revision = file.revision,
            locator = "{}",
            title = "A passage",
            quote = "The quiet forest",
            note = "Remember this",
            highlight = true,
        )
    private lateinit var storage: NotebookStorage

    @Before
    fun setup() {
        listOf("ebooks", "listening", "audio", "book-details").forEach {
            File(context.filesDir, it).deleteRecursively()
        }
        storage = NotebookStorage(context, owner)
        EbookStorage(context, owner).saveBook(book)
        EbookStorage(context, owner).content(file).writeText("content")
        ReadingNotes(context, owner, file.id).save(note)
    }

    private fun page(
        query: String = "",
        filter: NotebookFilter = NotebookFilter.ALL,
        number: Int = 1,
    ) = storage.page(query, filter, number)

    @Test
    fun `search combines case insensitive words across book author passage and note`() {
        assertEquals(1, page("SMITH forest Remember").entries.size)
        assertEquals(1, page("journey passage").entries.size)
        assertTrue(page("missing").entries.isEmpty())
        assertTrue(
            NotebookStorage(context, "other-server-account")
                .page("", NotebookFilter.ALL, 1)
                .entries
                .isEmpty()
        )
    }

    @Test
    fun `filters distinguish highlights notes and both kinds of bookmark`() {
        ReadingNotes(context, owner, file.id)
            .save(
                note.copy(
                    id = "bookmark",
                    title = "Bookmark",
                    highlight = false,
                    note = "",
                    quote = "",
                )
            )
        ListeningStorage(context, owner)
            .addBookmark(
                2,
                AudioBookmark(
                    id = "audio",
                    assetId = "asset",
                    positionMs = 65000,
                    manifestRevision = "r",
                    title = "Audio",
                ),
            )
        assertEquals(3, page().entries.size)
        assertEquals(
            listOf("A passage"),
            page(filter = NotebookFilter.HIGHLIGHTS).entries.map { it.title },
        )
        assertEquals(
            listOf("A passage"),
            page(filter = NotebookFilter.NOTES).entries.map { it.title },
        )
        assertEquals(
            listOf("Bookmark", "Audio"),
            page(filter = NotebookFilter.BOOKMARKS).entries.map { it.title },
        )
    }

    @Test
    fun `pages include reading and audio entries once without retaining a whole library`() {
        for (id in 1..64) ReadingNotes(context, owner, file.id)
            .save(note.copy(id = "note-$id", title = "Note $id"))
        ListeningStorage(context, owner)
            .addBookmark(
                2,
                AudioBookmark(
                    assetId = "asset",
                    positionMs = 1,
                    manifestRevision = "r",
                    title = "Audio",
                ),
            )
        val pages = (1..3).map { page(number = it) }
        assertEquals(listOf(30, 30, 6), pages.map { it.entries.size })
        assertEquals(listOf(true, true, false), pages.map { it.hasNext })
        assertEquals(66, pages.flatMap { it.entries }.map { it.key }.distinct().size)
        assertEquals(1, page("Audio").entries.size)
    }

    @Test
    fun `removed content and changed revisions preserve entries but block passage navigation`() {
        assertTrue(page().entries.single().canRead)
        assertEquals(book, storage.readingTarget(page().entries.single()))
        EbookStorage(context, owner).removeContent(file)
        val removed = page().entries.single()
        assertFalse(removed.canRead)
        assertEquals(note, removed.reading)
        assertThrows(IllegalStateException::class.java) { storage.readingTarget(removed) }
        EbookStorage(context, owner).saveBook(book.copy(file = file.copy(fileHash = "changed")))
        val changed = page().entries.single()
        assertFalse(changed.canRead)
        assertTrue(changed.location.contains("changed"))
        assertThrows(IllegalStateException::class.java) { storage.readingTarget(changed) }
    }

    @Test
    fun `edits and deletion reuse reader storage and preserve server copy identity`() {
        ReadingNotes(context, owner, file.id).save(note.copy(serverId = 42))
        val entry = page().entries.single()
        storage.save(entry, "Edited", "Updated text")
        val saved = ReadingNotes(context, owner, file.id).all().single()
        assertEquals("Edited", saved.title)
        assertEquals("Updated text", saved.note)
        assertEquals(42, saved.serverId)
        assertEquals(note.quote, saved.quote)
        assertThrows(IllegalStateException::class.java) { storage.save(entry, "Stale", "") }
        storage.delete(page().entries.single())
        assertTrue(ReadingNotes(context, owner, file.id).all().isEmpty())
    }

    @Test
    fun `audio bookmark survives manifest removal with cached book title and can be renamed`() {
        BookDetailsStorage(context, owner)
            .save(EbookDetail(2, "Audio title", listOf(EbookAuthor("Narrative writer"))))
        ListeningStorage(context, owner)
            .addBookmark(
                2,
                AudioBookmark(
                    id = "audio",
                    assetId = "asset",
                    positionMs = 65000,
                    manifestRevision = "r",
                    title = "Audio",
                ),
            )
        val entry = page("Narrative").entries.single()
        assertEquals("Audio title", entry.bookTitle)
        assertEquals("Audio location unavailable", entry.location)
        storage.save(entry, "Renamed", "")
        assertEquals("Renamed", ListeningStorage(context, owner).bookmarks(2).single().title)
        storage.delete(page("Renamed").entries.single())
        assertTrue(ListeningStorage(context, owner).bookmarks(2).isEmpty())
    }

    @Test
    fun `unreadable files do not hide healthy entries and atomic backups are discovered`() {
        File(context.filesDir, "ebooks/$owner/102").mkdirs()
        File(context.filesDir, "ebooks/$owner/102/notes.json").writeText("invalid")
        ListeningStorage(context, owner)
            .addBookmark(
                2,
                AudioBookmark(
                    assetId = "asset",
                    positionMs = 1,
                    manifestRevision = "r",
                    title = "Backup audio",
                ),
            )
        val audio = File(context.filesDir, "listening/$owner/2.json")
        audio.renameTo(File(audio.path + ".bak"))
        val result = page()
        assertEquals(1, result.unreadableFiles)
        assertEquals(2, result.entries.size)
        assertTrue(result.entries.any { it.title == "Backup audio" })
    }

    @Test
    fun `queries stop when cancelled`() {
        assertThrows(CancellationException::class.java) {
            storage.page("", NotebookFilter.ALL, 1) { throw CancellationException() }
        }
    }
}

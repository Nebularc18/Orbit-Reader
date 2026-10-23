package app.bookorbit.community.data

import android.content.Context
import app.bookorbit.community.reader.*

const val NOTEBOOK_PAGE_SIZE = 30

enum class NotebookFilter(val label: String) {
    ALL("All"),
    HIGHLIGHTS("Highlights"),
    NOTES("Notes"),
    BOOKMARKS("Bookmarks"),
}

data class NotebookEntry(
    val bookId: Int?,
    val bookTitle: String,
    val authors: List<String>,
    val fileId: Int? = null,
    val reading: ReadingNote? = null,
    val audio: AudioBookmark? = null,
    val canRead: Boolean = false,
    val location: String = "",
) {
    val key: String
        get() =
            if (reading != null) "ebook-$fileId-${reading.id}" else "audio-$bookId-${audio!!.id}"

    val title: String
        get() = reading?.title ?: audio!!.title

    val kind: String
        get() =
            if (audio != null) "Audio bookmark"
            else if (reading!!.highlight) "Highlight" else "Bookmark"

    fun matches(query: String, filter: NotebookFilter): Boolean {
        val included =
            when (filter) {
                NotebookFilter.ALL -> true
                NotebookFilter.HIGHLIGHTS -> reading?.highlight == true
                NotebookFilter.NOTES -> !reading?.note.isNullOrBlank()
                NotebookFilter.BOOKMARKS -> audio != null || reading?.highlight == false
            }
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val text =
            listOf(
                    bookTitle,
                    authors.joinToString(" "),
                    title,
                    reading?.quote.orEmpty(),
                    reading?.note.orEmpty(),
                )
                .joinToString("\n")
        return included && words.all { text.contains(it, ignoreCase = true) }
    }
}

data class NotebookPage(
    val entries: List<NotebookEntry>,
    val hasNext: Boolean,
    val unreadableFiles: Int,
)

class NotebookStorage(private val context: Context, private val owner: String) {
    private val ebooks = EbookStorage(context, owner)
    private val listening = ListeningStorage(context, owner)

    // Read one file's annotations at a time and retain only the requested page.
    fun page(
        query: String,
        filter: NotebookFilter,
        page: Int,
        checkActive: () -> Unit = {},
    ): NotebookPage {
        require(page >= 1)
        val results = mutableListOf<NotebookEntry>()
        var skipped = 0L
        var unreadable = 0
        fun add(entry: NotebookEntry) {
            checkActive()
            if (entry.matches(query, filter)) {
                if (skipped < (page.toLong() - 1) * NOTEBOOK_PAGE_SIZE) skipped++
                else results.add(entry)
            }
        }
        for (fileId in ebooks.savedFileIds()) {
            checkActive()
            try {
                val book = ebooks.book(fileId)
                val ready = book?.let { ebooks.ready(it.file) } == true
                for (note in ReadingNotes(context, owner, fileId).all()) {
                    val same = book?.file?.revision == note.revision
                    add(
                        NotebookEntry(
                            book?.bookId,
                            book?.title ?: "Ebook file $fileId",
                            book?.authors.orEmpty(),
                            fileId,
                            note,
                            canRead = ready && same,
                            location =
                                if (!same) "Book changed. Location unavailable."
                                else if (!ready)
                                    "Download this file from Read to reopen its location."
                                else "Saved reading location",
                        )
                    )
                    if (results.size > NOTEBOOK_PAGE_SIZE)
                        return NotebookPage(results.take(NOTEBOOK_PAGE_SIZE), true, unreadable)
                }
            } catch (e: java.io.IOException) {
                unreadable++
            } catch (e: IllegalArgumentException) {
                unreadable++
            }
        }
        val audio = AudioStorage(context, owner)
        val details = BookDetailsStorage(context, owner)
        for (bookId in listening.savedBookIds()) {
            checkActive()
            try {
                val bookmarks = listening.bookmarks(bookId)
                if (bookmarks.isEmpty()) continue
                val manifest = audio.manifest(bookId)
                val detail = if (manifest == null) details.read(bookId) else null
                for (bookmark in bookmarks) {
                    val part = manifest?.let { bookmark.target(it) }
                    add(
                        NotebookEntry(
                            bookId,
                            manifest?.book?.title ?: detail?.title ?: "Audiobook $bookId",
                            manifest?.book?.authors ?: detail?.authors?.map { it.name }.orEmpty(),
                            audio = bookmark,
                            location =
                                if (part == null) "Audio location unavailable"
                                else
                                    "Part ${part + 1} · ${bookmark.positionMs / 1000 / 60}:${(bookmark.positionMs / 1000 % 60).toString().padStart(2, '0')}",
                        )
                    )
                    if (results.size > NOTEBOOK_PAGE_SIZE)
                        return NotebookPage(results.take(NOTEBOOK_PAGE_SIZE), true, unreadable)
                }
            } catch (e: java.io.IOException) {
                unreadable++
            } catch (e: IllegalArgumentException) {
                unreadable++
            }
        }
        return NotebookPage(results, false, unreadable)
    }

    fun save(entry: NotebookEntry, title: String, note: String) {
        if (entry.reading != null) {
            val storage = ReadingNotes(context, owner, requireNotNull(entry.fileId))
            check(storage.all().any { it == entry.reading }) {
                "This note changed. Refresh Notebook and try again."
            }
            storage.save(entry.reading.copy(title = title, note = note))
        } else {
            val bookId = requireNotNull(entry.bookId)
            check(listening.bookmarks(bookId).any { it == entry.audio }) {
                "This bookmark changed. Refresh Notebook and try again."
            }
            listening.renameBookmark(bookId, requireNotNull(entry.audio).id, title)
        }
    }

    fun delete(entry: NotebookEntry) {
        if (entry.reading != null)
            ReadingNotes(context, owner, requireNotNull(entry.fileId)).delete(entry.reading.id)
        else listening.deleteBookmark(requireNotNull(entry.bookId), requireNotNull(entry.audio).id)
    }

    fun readingTarget(entry: NotebookEntry): SavedEbook {
        val book =
            ebooks.book(requireNotNull(entry.fileId)) ?: error("This ebook is no longer saved.")
        val note =
            ReadingNotes(context, owner, entry.fileId).all().firstOrNull {
                it.id == entry.reading?.id
            } ?: error("This note is no longer saved.")
        check(note.revision == book.file.revision && ebooks.ready(book.file)) {
            "Download the matching book file from Read before opening this location."
        }
        return book
    }
}

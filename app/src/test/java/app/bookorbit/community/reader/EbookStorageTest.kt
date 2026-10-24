package app.bookorbit.community.reader

import app.bookorbit.community.data.CatalogSort
import app.bookorbit.community.data.EbookFile
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EbookStorageTest {
    private val context
        get() = RuntimeEnvironment.getApplication()

    private lateinit var storage: EbookStorage
    private val file = EbookFile(101, "epub", "Book.epub", fileHash = "version-one")
    private val book = SavedEbook(2, "Sample", listOf("Writer"), file)

    @Test
    fun `sorted offline pages include every distinct book in global order`() {
        for (id in 1..35) for (edition in 0..1) {
            val entry =
                SavedEbook(
                    id,
                    "Book %02d".format(36 - id),
                    listOf("Writer"),
                    file.copy(id = id * 10 + edition),
                )
            storage.saveBook(entry)
            storage.content(entry.file).writeBytes(byteArrayOf(1))
        }
        val pages =
            storage.shelfBooks(1, CatalogSort.TITLE_ASC) +
                storage.shelfBooks(2, CatalogSort.TITLE_ASC)
        assertEquals((35 downTo 1).toList(), pages.map { it.bookId })
        assertEquals(
            (1..30).toList(),
            storage.shelfBooks(1, CatalogSort.TITLE_DESC).map { it.bookId },
        )
    }

    @Test
    fun `offline shelf pages distinct books and preserves all editions for opening`() {
        for (id in 1..35) for (edition in 0..1) {
            val entry = SavedEbook(id, "Book $id", emptyList(), file.copy(id = id * 10 + edition))
            storage.saveBook(entry)
            storage.content(entry.file).writeBytes(byteArrayOf(1))
        }
        assertEquals(30, storage.shelfBooks(1).size)
        assertEquals(5, storage.shelfBooks(2).size)
        assertEquals(
            35,
            (storage.shelfBooks(1) + storage.shelfBooks(2)).map { it.bookId }.distinct().size,
        )
        assertEquals(2, storage.filesForBook(31).size)
        assertTrue(storage.shelfBooks(3).isEmpty())
    }

    @Test
    fun `recent reading requires matching progress and ready content and stays account scoped`() {
        storage.saveBook(book)
        storage.content(file).writeBytes(byteArrayOf(1))
        assertTrue(storage.recentlyRead().isEmpty())
        storage.savePlace(file.id, ReadingPlace(file.revision, "{}"))
        assertEquals(listOf(book), storage.recentlyRead())
        assertTrue(EbookStorage(context, "other-account").recentlyRead().isEmpty())
        storage.savePlace(file.id, ReadingPlace("old-version", "{}"))
        assertTrue(storage.recentlyRead().isEmpty())
    }

    @Test
    fun `preferred edition survives recreation and stays scoped to account and book`() {
        storage.savePreferredFile(2, 101)
        assertEquals(101, EbookStorage(context, "server-a-account-a").preferredFile(2))
        assertNull(EbookStorage(context, "other-account").preferredFile(2))
        assertNull(storage.preferredFile(3))
    }

    @Before
    fun setup() {
        context.filesDir.resolve("ebooks").deleteRecursively()
        storage = EbookStorage(context, "server-a-account-a")
    }

    @Test
    fun `only completed ebooks appear and survive reopening in their account`() {
        storage.saveBook(book)
        File(storage.content(file).path + ".part").writeBytes(byteArrayOf(1))
        assertTrue(storage.books(1).isEmpty())
        storage.content(file).writeBytes(byteArrayOf(1, 2))
        assertEquals(listOf(book), EbookStorage(context, "server-a-account-a").books(1))
        assertTrue(storage.books(2).isEmpty())
        assertTrue(EbookStorage(context, "server-a-account-b").books(1).isEmpty())
        assertTrue(EbookStorage(context, "server-b-account-a").books(1).isEmpty())
    }

    @Test
    fun `positions are versioned and survive removal of the ebook`() {
        val place = ReadingPlace(file.revision, "{\"href\":\"chapter2.xhtml\"}")
        storage.savePlace(file.id, place)
        storage.content(file).writeText("cached book")
        storage.removeContent(file)
        assertFalse(storage.ready(file))
        assertEquals(place, EbookStorage(context, "server-a-account-a").place(file))
        assertNull(storage.place(file.copy(fileHash = "version-two")))
        assertNull(EbookStorage(context, "server-a-account-b").place(file))
        assertNotEquals(storage.content(file), storage.content(file.copy(fileHash = "version-two")))
    }

    @Test
    fun `reading preferences persist and reject invalid values`() {
        val preferences = ReadingPreferences(1.5, "sepia", true)
        storage.savePreferences(preferences)
        assertEquals(preferences, EbookStorage(context, "server-a-account-a").preferences())
        assertEquals(
            ReadingPreferences(),
            EbookStorage(context, "server-a-account-b").preferences(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            storage.savePreferences(preferences.copy(fontSize = Double.NaN))
        }
        assertThrows(IllegalArgumentException::class.java) {
            storage.savePreferences(preferences.copy(theme = "unknown"))
        }
        assertEquals(preferences, storage.preferences())
    }

    private fun zip(vararg paths: String): File {
        val archive = File(context.cacheDir, "test-ebook.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            for (path in paths) {
                zip.putNextEntry(ZipEntry(path))
                zip.write("test".toByteArray())
                zip.closeEntry()
            }
        }
        return archive
    }

    @Test
    fun `invalid archive paths and non EPUB archives are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            EbookStorage.validateArchive(zip("META-INF/container.xml", "../private.txt"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EbookStorage.validateArchive(zip("META-INF/container.xml", "C:/private.txt"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EbookStorage.validateArchive(zip("random.txt"))
        }
        EbookStorage.validateArchive(zip("META-INF/container.xml", "EPUB/chapter.xhtml"))
    }

    @Test
    fun `supported readers include FB2 and MOBI but exclude AZW3 and DJVU`() {
        assertTrue(file.supported)
        assertTrue(file.copy(format = "KEPUB").supported)
        assertTrue(file.copy(format = "pdf").supported)
        assertTrue(file.copy(format = "cbz").supported)
        assertTrue(file.copy(format = "cbr").supported)
        assertTrue(file.copy(format = "cb7").supported)
        assertTrue(file.copy(format = "mobi").supported)
        assertTrue(file.copy(format = "fb2").supported)
        assertFalse(file.copy(format = "azw3").supported)
        assertFalse(file.copy(format = "djvu").supported)
    }
}

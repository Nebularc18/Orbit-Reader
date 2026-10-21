package app.bookorbit.community.reader

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
    fun `EPUB and KEPUB files are supported while other formats wait`() {
        assertTrue(file.supported)
        assertTrue(file.copy(format = "KEPUB").supported)
        assertTrue(file.copy(format = "pdf").supported)
        assertTrue(file.copy(format = "cbz").supported)
        assertTrue(file.copy(format = "cbr").supported)
        assertTrue(file.copy(format = "cb7").supported)
        assertFalse(file.copy(format = "mobi").supported)
    }
}

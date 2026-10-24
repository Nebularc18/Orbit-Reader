package app.bookorbit.community.upload

import app.bookorbit.community.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UploadStorageTest {
    private val caps =
        UploadCapabilities(
            1000,
            100,
            listOf("epub", "pdf"),
            true,
            listOf(UploadLibrary(1, "Library", listOf("epub"), listOf(UploadFolder(2, "Books")))),
        )

    @Test
    fun `file validation intersects server and library formats and checks target size`() {
        val target = UploadTarget(libraryId = 1, folderId = 2)
        validateUploadFile("book.EPUB", 1000, target, caps)
        for ((name, size) in
            listOf(
                "book.pdf" to 1L,
                "book.epub" to 1001L,
                "book.epub" to 0L,
                "../book.epub" to 1L,
            )) assertThrows(Exception::class.java) { validateUploadFile(name, size, target, caps) }
        assertThrows(Exception::class.java) {
            validateUploadFile("book.epub", 10, target.copy(folderId = 3), caps)
        }
        assertThrows(Exception::class.java) {
            validateUploadFile("book.epub", 10, target, caps.copy(canUploadToLibrary = false))
        }
    }

    @Test
    fun `durable upload records and staged bytes remain account scoped`() {
        val app = RuntimeEnvironment.getApplication()
        val first = UploadStorage(app, "first-account")
        val second = UploadStorage(app, "second-account")
        val job =
            UploadJob(
                uri = "content://test/book",
                filename = "book.epub",
                size = 3,
                target = UploadTarget(libraryId = 1),
                remoteId = "01234567-89ab-4cde-8fab-0123456789ab",
                bytes = 2,
            )
        first.save(job)
        first.content(job.id).writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(job, first.read(job.id))
        assertTrue(second.all().isEmpty())
        assertFalse(second.content(job.id).exists())
        first.remove(job.id)
        assertFalse(first.update(job.copy(bytes = 3)))
        assertTrue(first.all().isEmpty())
        assertThrows(Exception::class.java) { first.content("../escape") }
    }

    @Test
    fun `polling cannot interrupt atomic upload progress writes`() {
        val context = RuntimeEnvironment.getApplication()
        val first = UploadStorage(context, "concurrent-account")
        val second = UploadStorage(context, "concurrent-account")
        val job =
            UploadJob(
                uri = "content://test/concurrent",
                filename = "book.epub",
                size = 100,
                target = UploadTarget(libraryId = 1),
            )
        first.save(job)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val writer = pool.submit { for (value in 1L..100L) first.save(job.copy(bytes = value)) }
            val reader =
                pool.submit { repeat(100) { assertTrue(second.read(job.id).bytes in 0..100) } }
            writer.get(10, java.util.concurrent.TimeUnit.SECONDS)
            reader.get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(100L, second.read(job.id).bytes)
        } finally {
            pool.shutdownNow()
            first.remove(job.id)
        }
    }
}

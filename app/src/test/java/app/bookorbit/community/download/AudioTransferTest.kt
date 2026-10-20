package app.bookorbit.community.download

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AudioTransferTest {
    private val server = MockWebServer().apply { start() }
    private val directory = Files.createTempDirectory("audio-transfer").toFile()
    private val file = File(directory, "book.audio")
    private val client = OkHttpClient()

    @After
    fun cleanup() {
        server.shutdown()
        directory.deleteRecursively()
    }

    private fun partial(text: String, validator: String? = "\"v1\"") {
        File(file.path + ".part").writeText(text)
        validator?.let { File(file.path + ".part.etag").writeText(it) }
    }

    private fun transfer(expected: Long? = 6, stopAt: Long? = null) = runBlocking {
        AudioTransfer()
            .download(
                file,
                expected,
                request = { offset, validator ->
                    client
                        .newCall(
                            Request.Builder()
                                .url(server.url("/audio"))
                                .apply {
                                    if (offset > 0 && validator != null) {
                                        header("Range", "bytes=$offset-")
                                        header("If-Range", validator)
                                    }
                                }
                                .build()
                        )
                        .execute()
                },
                checkActive = {},
                progress = { bytes, _ ->
                    if (stopAt != null && bytes >= stopAt)
                        throw IOException("Connection interrupted")
                },
            )
    }

    @Test
    fun `resume appends exactly the remaining bytes using the saved HTTP validator`() {
        partial("abc")
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("ETag", "\"v1\"")
                .setHeader("Content-Range", "bytes 3-5/6")
                .setBody("def")
        )
        transfer()
        assertEquals("abcdef", file.readText())
        val request = server.takeRequest()
        assertEquals("bytes=3-", request.getHeader("Range"))
        assertEquals("\"v1\"", request.getHeader("If-Range"))
        assertFalse(File(file.path + ".part").exists())
    }

    @Test
    fun `server ignoring range or changing validator replaces the partial file`() {
        partial("abc")
        server.enqueue(MockResponse().setHeader("ETag", "\"v2\"").setBody("UVWXYZ"))
        transfer()
        assertEquals("UVWXYZ", file.readText())
    }

    @Test
    fun `partial without a strong validator restarts safely`() {
        partial("abc", "W/\"v1\"")
        server.enqueue(MockResponse().setBody("abcdef"))
        transfer()
        assertNull(server.takeRequest().getHeader("Range"))
        assertEquals("abcdef", file.readText())
    }

    @Test
    fun `interruption preserves bytes and validator for the next run`() {
        server.enqueue(MockResponse().setHeader("ETag", "\"v1\"").setBody("abc"))
        try {
            transfer(expected = null, stopAt = 3)
            fail("Expected interruption")
        } catch (_: IOException) {}
        assertFalse(file.exists())
        assertEquals("abc", File(file.path + ".part").readText())
        assertEquals("\"v1\"", File(file.path + ".part.etag").readText())
    }

    @Test
    fun `wrong range offset is never appended or marked complete`() {
        partial("abc")
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("ETag", "\"v1\"")
                .setHeader("Content-Range", "bytes 2-4/6")
                .setBody("def")
        )
        try {
            transfer()
            fail("Expected range rejection")
        } catch (_: IOException) {}
        assertFalse(file.exists())
        assertFalse(File(file.path + ".part").exists())
    }

    @Test
    fun `changed validator on a partial response is rejected`() {
        partial("abc")
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("ETag", "\"v2\"")
                .setHeader("Content-Range", "bytes 3-5/6")
                .setBody("def")
        )
        try {
            transfer()
            fail("Expected validator rejection")
        } catch (_: IOException) {}
        assertFalse(file.exists())
    }

    @Test
    fun `manifest length mismatch never becomes an offline file`() {
        server.enqueue(MockResponse().setBody("abc"))
        try {
            transfer()
            fail("Expected length rejection")
        } catch (_: IOException) {}
        assertFalse(file.exists())
    }

    @Test
    fun `unknown manifest length uses the range total to detect an incomplete response`() {
        partial("abc")
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("ETag", "\"v1\"")
                .setHeader("Content-Range", "bytes 3-4/6")
                .setBody("de")
        )
        try {
            transfer(expected = null)
            fail("Expected incomplete download")
        } catch (_: IOException) {}
        assertFalse(file.exists())
        assertEquals("abcde", File(file.path + ".part").readText())
    }
}

@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package app.bookorbit.community.reader

import androidx.work.ListenableWorker.Result
import app.bookorbit.community.data.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AutomaticReadingSyncTest {
    private lateinit var server: MockWebServer
    private lateinit var api: OrbitApi
    private lateinit var storage: EbookStorage
    private lateinit var owner: String
    private var session: Session? = null
    private val file = EbookFile(101, "epub", fileHash = "v1")
    private val baseline = EbookProgress(10.0, cfi = "old", textUpdatedAt = "before")
    private val value = EbookProgressWrite(20.0, cfi = "new")
    private val saved = EbookProgress(20.0, cfi = "new", textUpdatedAt = "after")

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.filesDir.resolve("ebooks").deleteRecursively()
        server = MockWebServer().apply { start() }
        session =
            Session(
                server.url("/").toString().trimEnd('/'),
                "test-access",
                "test-refresh",
                Account(7, username = "reader"),
            )
        owner = session!!.scope
        api =
            OrbitApi(
                object : SessionStore {
                    override fun read() = session

                    override fun save(session: Session?) {
                        this@AutomaticReadingSyncTest.session = session
                    }
                }
            )
        storage = EbookStorage(context, owner)
        storage.saveBook(SavedEbook(2, "Book", emptyList(), file))
        storage.prepareReadingUpload(file, baseline)
        storage.checkpointReadingUpload(file, value)
    }

    @After
    fun cleanup() {
        server.shutdown()
    }

    private fun detail(hash: String = "v1") {
        server.enqueue(
            MockResponse()
                .setBody("""{"id":2,"files":[{"id":101,"format":"epub","fileHash":"$hash"}]}""")
        )
    }

    private fun progress(value: EbookProgress) {
        server.enqueue(MockResponse().setBody(wireJson.encodeToString(value)))
    }

    private fun sync() = syncAutomaticReadingProgress(api, storage, owner, file.id)

    @Test
    fun `saved position survives recreation and publishes exact wire fields`() {
        val reopened = EbookStorage(RuntimeEnvironment.getApplication(), owner)
        assertEquals(value, reopened.readingUpload(file.id)?.pending)
        detail()
        progress(baseline)
        server.enqueue(MockResponse().setBody("{}"))
        progress(saved)
        assertEquals(Result.success(), syncAutomaticReadingProgress(api, reopened, owner, file.id))
        assertNull(reopened.readingUpload(file.id)?.pending)
        assertEquals(saved, reopened.readingUpload(file.id)?.baseline)
        server.takeRequest()
        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/books/files/101/progress", request.path)
        assertEquals(wireJson.encodeToString(value), request.body.readUtf8())
    }

    @Test
    fun `another device changing only timestamp blocks the write and survives reopening`() {
        detail()
        progress(baseline.copy(textUpdatedAt = "other-device"))
        assertNotEquals(Result.success(), sync())
        assertEquals(2, server.requestCount)
        val reopened = EbookStorage(RuntimeEnvironment.getApplication(), owner)
        assertTrue(reopened.readingUpload(file.id)!!.blocked)
        reopened.prepareReadingUpload(file, baseline.copy(percentage = 99.0))
        assertEquals(baseline, reopened.readingUpload(file.id)?.baseline)
        assertEquals(value, reopened.readingUpload(file.id)?.pending)
    }

    @Test
    fun `changed file revision and switched account cannot upload`() {
        detail("v2")
        assertNotEquals(Result.success(), sync())
        assertEquals(1, server.requestCount)
        session = null
        assertNotEquals(Result.success(), sync())
        assertEquals(1, server.requestCount)
        assertNull(
            EbookStorage(RuntimeEnvironment.getApplication(), "other-account")
                .readingUpload(file.id)
        )
    }

    @Test
    fun `lost write response is acknowledged without replaying a matching position`() {
        detail()
        progress(saved)
        assertEquals(Result.success(), sync())
        assertEquals(2, server.requestCount)
        assertNull(storage.readingUpload(file.id)?.pending)
    }

    @Test
    fun `temporary server failure retains pending position for retry`() {
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(Result.retry(), sync())
        assertEquals(value, storage.readingUpload(file.id)?.pending)
        assertFalse(storage.readingUpload(file.id)!!.blocked)
    }

    @Test
    fun `newer local checkpoint survives acknowledgment of an in flight position`() {
        val sent = storage.readingUpload(file.id)!!
        val newer = value.copy(percentage = 25.0, cfi = "newer")
        storage.checkpointReadingUpload(file, newer)
        storage.acknowledgeReadingUpload(file.id, sent, saved)
        assertEquals(newer, storage.readingUpload(file.id)?.pending)
        assertEquals(saved, storage.readingUpload(file.id)?.baseline)
        storage.prepareReadingUpload(file, saved.copy(percentage = 90.0))
        assertEquals(saved, storage.readingUpload(file.id)?.baseline)
    }

    @Test
    fun `offline first open requires review instead of assuming current server position`() {
        val other = file.copy(id = 102)
        storage.prepareReadingUpload(other, null)
        storage.checkpointReadingUpload(other, value)
        assertNotEquals(
            Result.success(),
            syncAutomaticReadingProgress(api, storage, owner, other.id),
        )
        assertEquals(0, server.requestCount)
        assertTrue(storage.readingUpload(other.id)!!.blocked)
    }

    @Test
    fun `server float precision does not turn a successful upload into a conflict`() {
        val fractional = value.copy(percentage = 100.0 / 3)
        storage.checkpointReadingUpload(file, fractional)
        val rounded = saved.copy(percentage = fractional.percentage.toFloat().toDouble())
        detail()
        progress(baseline)
        server.enqueue(MockResponse().setBody("{}"))
        progress(rounded)
        assertEquals(Result.success(), sync())
        assertNull(storage.readingUpload(file.id)?.pending)
        assertFalse(storage.readingUpload(file.id)!!.blocked)
    }

    @Test
    fun `background upload resolves a durable EPUB source location`() {
        ZipOutputStream(storage.content(file).outputStream()).use { zip ->
            mapOf(
                    "META-INF/container.xml" to
                        "<container><rootfiles><rootfile full-path=\"book.opf\"/></rootfiles></container>",
                    "book.opf" to
                        "<package><metadata/><manifest><item id=\"one\" href=\"one.xhtml\"/></manifest><spine><itemref idref=\"one\"/></spine></package>",
                    "one.xhtml" to "<html><head/><body><p>Hello reader</p></body></html>",
                )
                .forEach { (name, contents) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(contents.toByteArray())
                    zip.closeEntry()
                }
        }
        val cfi = "epubcfi(/6/2!/4/2/1:3)"
        val locator = EpubCfi(storage.content(file)).toLocator(cfi)!!
        storage.checkpointReadingUpload(file, value.copy(cfi = null), locator.toJSON().toString())
        detail()
        progress(baseline)
        server.enqueue(MockResponse().setBody("{}"))
        progress(saved.copy(cfi = cfi))
        assertEquals(Result.success(), sync())
        server.takeRequest()
        server.takeRequest()
        assertEquals(
            wireJson.encodeToString(value.copy(cfi = cfi)),
            server.takeRequest().body.readUtf8(),
        )
        assertNull(storage.readingUpload(file.id)?.pending)
    }

    @Test
    fun `using server position discards pending work and explicit publish clears conflict`() {
        storage.blockReadingUpload(file.id)
        storage.resetReadingUpload(file.id, baseline, discardPending = true)
        assertNull(storage.readingUpload(file.id)?.pending)
        assertFalse(storage.readingUpload(file.id)!!.blocked)
        storage.checkpointReadingUpload(file, value)
        storage.blockReadingUpload(file.id)
        storage.resetReadingUpload(file.id, saved)
        assertNull(storage.readingUpload(file.id)?.pending)
        assertFalse(storage.readingUpload(file.id)!!.blocked)
    }
}

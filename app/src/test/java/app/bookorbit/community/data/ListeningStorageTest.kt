package app.bookorbit.community.data

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ListeningStorageTest {
    private val context
        get() = RuntimeEnvironment.getApplication()

    private lateinit var storage: ListeningStorage
    private val bookmark =
        AudioBookmark(
            assetId = "second",
            positionMs = 7500,
            manifestRevision = "revision",
            title = "A useful passage",
        )

    @Before
    fun setup() {
        context.filesDir.resolve("listening").deleteRecursively()
        storage = ListeningStorage(context, "server-a-account-a")
    }

    @Test
    fun `speed is saved independently per book and scoped to server and account`() {
        storage.saveSpeed(1, 1.5f)
        storage.saveSpeed(2, 2f)
        val reopened = ListeningStorage(context, "server-a-account-a")
        assertEquals(1.5f, reopened.speed(1))
        assertEquals(2f, reopened.speed(2))
        assertEquals(1f, reopened.speed(3))
        assertEquals(1f, ListeningStorage(context, "server-b-account-a").speed(1))
        assertEquals(1f, ListeningStorage(context, "server-a-account-b").speed(1))
        assertThrows(IllegalArgumentException::class.java) { storage.saveSpeed(1, Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { storage.saveSpeed(1, 0f) }
        assertEquals(1.5f, storage.speed(1))
    }

    @Test
    fun `bookmarks can be renamed and removed without overwriting speed`() {
        storage.saveSpeed(1, 1.75f)
        storage.addBookmark(1, bookmark)
        val reopened = ListeningStorage(context, "server-a-account-a")
        assertEquals(bookmark, reopened.bookmarks(1).single())
        reopened.renameBookmark(1, bookmark.id, "  New name <script>  ")
        assertEquals("New name <script>", reopened.bookmarks(1).single().title)
        assertEquals(bookmark.assetId, reopened.bookmarks(1).single().assetId)
        assertEquals(bookmark.positionMs, reopened.bookmarks(1).single().positionMs)
        assertEquals(bookmark.id, reopened.bookmarks(1).single().id)
        reopened.saveSpeed(1, 2f)
        assertEquals(1, reopened.bookmarks(1).size)
        reopened.deleteBookmark(1, bookmark.id)
        assertTrue(reopened.bookmarks(1).isEmpty())
        assertEquals(2f, reopened.speed(1))
    }

    @Test
    fun `bookmark validation leaves previously saved data intact`() {
        storage.addBookmark(1, bookmark)
        assertThrows(IllegalArgumentException::class.java) {
            storage.renameBookmark(1, bookmark.id, " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            storage.addBookmark(1, bookmark.copy(title = "x".repeat(121)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            storage.addBookmark(1, bookmark.copy(positionMs = -1))
        }
        assertEquals(listOf(bookmark), storage.bookmarks(1))
        assertTrue(storage.bookmarks(2).isEmpty())
        assertTrue(ListeningStorage(context, "server-a-account-b").bookmarks(1).isEmpty())
        assertTrue(ListeningStorage(context, "server-b-account-a").bookmarks(1).isEmpty())
    }

    @Test
    fun `download removal preserves bookmarks speed and skip preferences`() {
        storage.addBookmark(1, bookmark)
        storage.saveSpeed(1, 1.25f)
        storage.saveIntervals(SkipIntervals(10, 45))
        AudioStorage(context, "server-a-account-a").deleteBook(1)
        val reopened = ListeningStorage(context, "server-a-account-a")
        assertEquals(listOf(bookmark), reopened.bookmarks(1))
        assertEquals(1.25f, reopened.speed(1))
        assertEquals(SkipIntervals(10, 45), reopened.intervals())
    }

    @Test
    fun `skip intervals persist and reject unsupported values`() {
        assertEquals(SkipIntervals(15, 30), storage.intervals())
        storage.saveIntervals(SkipIntervals(5, 60))
        assertEquals(
            SkipIntervals(5, 60),
            ListeningStorage(context, "server-a-account-a").intervals(),
        )
        assertEquals(SkipIntervals(), ListeningStorage(context, "server-a-account-b").intervals())
        assertThrows(IllegalArgumentException::class.java) {
            storage.saveIntervals(SkipIntervals(0, 30))
        }
        assertEquals(SkipIntervals(5, 60), storage.intervals())
    }

    @Test
    fun `bookmark targets retain file offsets and reject changed manifests or missing files`() {
        val manifest =
            AudioManifest(
                "bookorbit.audiobook-manifest",
                2,
                "revision",
                AudioBook(1, "Test"),
                listOf(
                    AudioAsset("first", 1, 0, "mp3", null, 1, "a"),
                    AudioAsset("second", 2, 1, "mp3", null, 1, "b"),
                ),
                emptyList(),
                0,
            )
        assertEquals(1, bookmark.target(manifest))
        assertNull(bookmark.target(manifest.copy(revision = "new")))
        assertNull(bookmark.target(manifest.copy(assets = listOf(manifest.assets[0]))))
    }

    @Test
    fun `bookmark limit rejects additions without losing existing bookmarks`() {
        val saved = List(ListeningStorage.MAX_BOOKMARKS) { bookmark.copy(id = "bookmark-$it") }
        context.filesDir
            .resolve("listening/server-a-account-a/1.json")
            .writeText("{\"bookmarks\":" + wireJson.encodeToString(saved) + "}")
        assertThrows(IllegalArgumentException::class.java) { storage.addBookmark(1, bookmark) }
        assertEquals(saved, storage.bookmarks(1))
        storage.deleteBookmark(1, saved.first().id)
        storage.addBookmark(1, bookmark)
        assertEquals(ListeningStorage.MAX_BOOKMARKS, storage.bookmarks(1).size)
    }
}

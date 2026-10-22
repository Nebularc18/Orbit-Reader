package app.bookorbit.community.data

import app.bookorbit.community.download.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlaybackToolsTest {
    private val context
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun reset() {
        context.getSharedPreferences("playback-tools", 0).edit().clear().commit()
        context.filesDir.resolve("audio").deleteRecursively()
        context.filesDir.resolve("ebooks").deleteRecursively()
    }

    @Test
    fun `queue persists order deduplicates and isolates accounts`() {
        val first = PlaybackToolsStorage(context, "server-a-account")
        first.add(QueuedBook(1, "One"))
        first.add(QueuedBook(2, "Two"))
        first.add(QueuedBook(2, "Two"), true)
        assertEquals(
            listOf(2, 1),
            PlaybackToolsStorage(context, "server-a-account").queue().map { it.id },
        )
        first.move(1, -1)
        assertEquals(listOf(1, 2), first.queue().map { it.id })
        first.remove(1)
        assertEquals(listOf(2), first.queue().map { it.id })
        assertTrue(PlaybackToolsStorage(context, "server-b-account").queue().isEmpty())
    }

    @Test
    fun `queue is bounded but existing books can be moved to front`() {
        val store = PlaybackToolsStorage(context, "a")
        (1..50).forEach { store.add(QueuedBook(it, "Book")) }
        assertThrows(IllegalArgumentException::class.java) { store.add(QueuedBook(51, "Extra")) }
        store.add(QueuedBook(50, "Last"), true)
        assertEquals(50, store.queue().size)
        assertEquals(50, store.queue().first().id)
    }

    @Test
    fun `options are opt in validated durable and account scoped`() {
        val store = PlaybackToolsStorage(context, "a")
        assertEquals(PlaybackOptions(), store.options())
        val options = PlaybackOptions(smartResume = true, autoplayQueue = true, storageGiB = 1)
        store.options(options)
        assertEquals(options, PlaybackToolsStorage(context, "a").options())
        assertEquals(PlaybackOptions(), PlaybackToolsStorage(context, "b").options())
        assertThrows(IllegalArgumentException::class.java) {
            store.options(options.copy(storageGiB = -1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.options(options.copy(rewindSeconds = 600))
        }
    }

    @Test
    fun `smart rewind starts at a minute and never accepts arbitrary intervals`() {
        assertEquals(0L, smartRewindMillis(true, 59999, 10))
        assertEquals(10000L, smartRewindMillis(true, 60000, 10))
        assertEquals(0L, smartRewindMillis(false, Long.MAX_VALUE, 30))
        assertEquals(0L, smartRewindMillis(true, Long.MAX_VALUE, 999))
    }

    @Test
    fun `storage reservations include concurrent downloads and remain account scoped`() {
        PlaybackToolsStorage(context, "a").options(PlaybackOptions(storageGiB = 1))
        val budget = OfflineBudget(context)
        val cap = 1024L * 1024 * 1024
        budget.reserve("a", "first", cap - 10)
        assertThrows(OfflineLimitReached::class.java) { budget.reserve("a", "second", 11) }
        budget.reserve("b", "other", cap * 2)
        budget.release("first")
        budget.reserve("a", "second", cap)
        val media =
            context.filesDir.resolve("audio/a/book/file.audio").apply {
                parentFile!!.mkdirs()
                writeBytes(ByteArray(8))
            }
        context.filesDir.resolve("audio/a/book/manifest.json").writeText("metadata")
        assertEquals(8L, budget.used("a"))
        assertEquals(0L, budget.used("b"))
        assertTrue(media.exists())
    }
}

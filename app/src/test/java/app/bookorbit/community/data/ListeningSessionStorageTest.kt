package app.bookorbit.community.data

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ListeningSessionStorageTest {
    private val context
        get() = RuntimeEnvironment.getApplication()

    private fun pending(seconds: Int) =
        PendingListeningSession(
            42,
            ListeningSessionWrite(
                "session-1",
                "2026-10-25T23:59:00Z",
                "2026-10-26T00:01:00Z",
                seconds,
                "listen",
                "android",
            ),
        )

    @Before
    fun reset() {
        context.filesDir.resolve("listening-sessions").deleteRecursively()
    }

    @Test
    fun `queue survives reopen and isolates accounts`() {
        ListeningSessionStorage(context, "server-account-one").save(pending(12))
        assertEquals(
            listOf(pending(12)),
            ListeningSessionStorage(context, "server-account-one").pending(),
        )
        assertTrue(ListeningSessionStorage(context, "server-account-two").pending().isEmpty())
    }

    @Test
    fun `acknowledging a stale upload preserves a newer checkpoint`() {
        val storage = ListeningSessionStorage(context, "one")
        storage.save(pending(12))
        storage.save(pending(17))
        storage.acknowledge(pending(12))
        assertEquals(listOf(pending(17)), storage.pending())
        storage.acknowledge(pending(17))
        assertTrue(storage.pending().isEmpty())
    }

    @Test
    fun `batch reads remain bounded and backup recovers interrupted writes`() {
        val storage = ListeningSessionStorage(context, "one")
        for (id in 1..4) storage.save(
            pending(12).let { it.copy(value = it.value.copy(sessionId = "session-$id")) }
        )
        assertEquals(2, storage.pending(2).size)
        val root = context.filesDir.resolve("listening-sessions/one")
        assertTrue(root.resolve("session-1").renameTo(root.resolve("session-1.bak")))
        root.resolve("session-2.new").writeText("interrupted write")
        assertEquals(4, storage.pending().size)
    }
}

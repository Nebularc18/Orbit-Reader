package app.bookorbit.community.playback

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ListeningSessionTrackerTest {
    private val start = Instant.parse("2026-10-25T23:59:50Z")

    @Test
    fun `checkpoints are cumulative and pause excludes idle time`() {
        val tracker = ListeningSessionTracker()
        assertNull(tracker.sample("one", 10, true, start, 0))
        assertNull(tracker.sample("one", 10, true, start.plusSeconds(5), 5000))
        val first = tracker.sample("one", 10, true, start.plusSeconds(12), 12000)!!
        val paused = tracker.sample("one", 10, false, start.plusSeconds(17), 17000)!!
        assertEquals(12, first.value.durationSeconds)
        assertEquals(17, paused.value.durationSeconds)
        assertEquals(first.value.sessionId, paused.value.sessionId)
        assertEquals(start.toString(), paused.value.startedAt)
        assertNull(tracker.sample("one", 10, false, start.plusSeconds(100), 100000))
        assertNull(tracker.sample("one", 10, true, start.plusSeconds(110), 110000))
        val resumed = tracker.sample("one", 10, false, start.plusSeconds(122), 122000)!!
        assertEquals(12, resumed.value.durationSeconds)
        assertNotEquals(first.value.sessionId, resumed.value.sessionId)
    }

    @Test
    fun `file boundaries and account changes finish the previous session`() {
        val tracker = ListeningSessionTracker()
        tracker.sample("one", 10, true, start, 0)
        val old = tracker.sample("one", 11, true, start.plusSeconds(20), 20000)!!
        assertEquals(10, old.fileId)
        val next = tracker.sample("two", 11, true, start.plusSeconds(35), 35000)!!
        assertEquals(11, next.fileId)
        assertEquals("one", next.owner)
        assertEquals(15, next.value.durationSeconds)
        val switched = tracker.sample(null, null, false, start.plusSeconds(45), 45000)!!
        assertEquals("two", switched.owner)
        assertEquals(10, switched.value.durationSeconds)
    }

    @Test
    fun `long playback rotates IDs and clock corrections never inflate duration`() {
        val tracker = ListeningSessionTracker()
        tracker.sample("one", 10, true, start, 0)
        val first = tracker.sample("one", 10, true, start.plusSeconds(300), 300000)!!
        assertEquals(300, first.value.durationSeconds)
        val next = tracker.sample("one", 10, true, start.plusSeconds(310), 310000)!!
        assertNotEquals(first.value.sessionId, next.value.sessionId)
        assertEquals(10, next.value.durationSeconds)
        assertNull(tracker.sample("one", 10, true, start.minusSeconds(20), 320000))
        val corrected = tracker.sample("one", 10, false, start, 330000)!!
        assertEquals(10, corrected.value.durationSeconds)
    }
}

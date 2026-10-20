package app.bookorbit.community.playback

import app.bookorbit.community.data.*
import org.junit.Assert.*
import org.junit.Test

class AudioTimelineTest {
    private val assets =
        listOf(
            AudioAsset("first", 1, 0, "mp3", 20000, 1, "a"),
            AudioAsset("second", 2, 1, "mp3", 30000, 1, "b"),
        )
    private val manifest =
        AudioManifest(
            "bookorbit.audiobook-manifest",
            2,
            "revision",
            AudioBook(1, "Sample"),
            assets,
            listOf(
                AudioChapter("one", "One", "first", 0, 0, 15000, 0),
                AudioChapter("two", "Two", "first", 1, 15000, 35000, 15000),
                AudioChapter("three", "Three", "second", 2, 35000, 50000, 15000),
            ),
            50000,
        )
    private val timeline = AudioTimeline(manifest)

    @Test
    fun `whole-book seek maps to the correct file and local position`() {
        assertEquals(AudioSeek(1, 15000), timeline.seek(35000))
        assertEquals(35000L, timeline.elapsed(1, 15000))
    }

    @Test
    fun `seeking exactly to a file boundary starts the next file`() {
        assertEquals(AudioSeek(1, 0), timeline.seek(20000))
    }

    @Test
    fun `chapter seek can cross a file boundary inside one chapter`() {
        val chapter = manifest.chapters[1]
        assertEquals(AudioSeek(1, 5000), timeline.seek(chapter.startMs + 10000))
        assertEquals(1, timeline.chapterIndex(25000))
    }

    @Test
    fun `chapter boundaries select the next chapter and retain the final chapter at the end`() {
        assertEquals(0, timeline.chapterIndex(14999))
        assertEquals(1, timeline.chapterIndex(15000))
        assertEquals(2, timeline.chapterIndex(50000))
        assertEquals(-1, timeline.chapterIndex(50001))
    }

    @Test
    fun `seeks are clamped at both ends of the audiobook`() {
        assertEquals(AudioSeek(0, 0), timeline.seek(-100))
        assertEquals(AudioSeek(1, 30000), timeline.seek(99999))
    }

    @Test
    fun `chapter markers represent whole-book chapter boundaries`() {
        assertEquals(listOf(0.3f, 0.7f), timeline.chapterFractions)
    }

    @Test
    fun `unknown file durations disable global seeking instead of guessing offsets`() {
        val unknown =
            AudioTimeline(
                manifest.copy(assets = listOf(assets[0].copy(durationMs = null), assets[1]))
            )
        assertFalse(unknown.hasDurations)
        assertNull(unknown.seek(25000))
        assertTrue(unknown.chapterFractions.isEmpty())
    }

    @Test
    fun `skip crosses files and chapters in both directions and clamps at book ends`() {
        assertEquals(AudioSeek(1, 10000), timeline.skip(0, 15000, 15000, 20000))
        assertEquals(AudioSeek(0, 10000), timeline.skip(1, 5000, -15000, 30000))
        assertEquals(AudioSeek(0, 0), timeline.skip(0, 5000, -60000, 20000))
        assertEquals(AudioSeek(1, 30000), timeline.skip(1, 25000, 60000, 30000))
    }

    @Test
    fun `skip with unknown durations stays in current file and uses runtime duration when available`() {
        val unknown =
            AudioTimeline(
                manifest.copy(assets = listOf(assets[0].copy(durationMs = null), assets[1]))
            )
        assertEquals(AudioSeek(0, 20000), unknown.skip(0, 15000, 30000, 20000))
        assertEquals(AudioSeek(1, 0), unknown.skip(1, 5000, -15000, 30000))
        assertEquals(AudioSeek(0, 45000), unknown.skip(0, 15000, 30000, -1))
    }
}

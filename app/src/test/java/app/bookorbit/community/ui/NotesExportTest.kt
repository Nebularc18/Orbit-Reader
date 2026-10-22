package app.bookorbit.community.ui

import app.bookorbit.community.playback.*
import app.bookorbit.community.reader.ReadingNote
import org.junit.Assert.*
import org.junit.Test

class NotesExportTest {
    @Test
    fun `markdown export escapes user markup and includes quotes without private locators`() {
        val note =
            ReadingNote(
                revision = "revision-private",
                locator = "locator-private",
                title = "[Title]",
                quote = "<script>\nSecond line",
                note = "**literal**",
            )
        val markdown = exportReadingNotes("Book", listOf(note), true)
        assertTrue(markdown.startsWith("# Book"))
        assertTrue(markdown.contains("\\[Title\\]"))
        assertTrue(markdown.contains("> &lt;script&gt;\n> Second line"))
        assertTrue(markdown.contains("\\*\\*literal\\*\\*"))
        assertFalse(markdown.contains("locator-private"))
        assertFalse(markdown.contains("revision-private"))
        val plain = exportReadingNotes("Book", listOf(note), false)
        assertTrue(plain.contains("**literal**"))
        assertTrue(plain.contains("<script>"))
    }

    @Test
    fun `car identifiers reject stale accounts malformed IDs and invalid books`() {
        assertEquals(42, parseCarBookId(carBookId("a", 42), "a"))
        assertNull(parseCarBookId(carBookId("a", 42), "b"))
        assertNull(parseCarBookId("orbit-book:a:0", "a"))
        assertNull(parseCarBookId("orbit-book:a:-1", "a"))
        assertNull(parseCarBookId("orbit-book:a:42/other", "a"))
        assertNull(parseCarBookId("https://example.org", "a"))
    }
}

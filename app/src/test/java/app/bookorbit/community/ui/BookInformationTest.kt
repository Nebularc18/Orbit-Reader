package app.bookorbit.community.ui

import org.junit.Assert.*
import org.junit.Test

class BookInformationTest {
    @Test
    fun `synopsis preserves readable text without active elements or HTML`() {
        val text =
            bookDescription(
                "<p>First &amp; second.</p><script>alert('bad')</script><style>body{}</style><p>Next <b>paragraph</b>.</p><img src='https://example.com/private'>"
            )
        assertTrue(text.contains("First & second."))
        assertTrue(text.contains("Next paragraph."))
        assertTrue(text.contains("\n"))
        assertFalse(text.contains("alert"))
        assertFalse(text.contains("<"))
        assertFalse(text.contains("https://"))
    }
}

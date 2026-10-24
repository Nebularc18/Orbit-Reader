package app.bookorbit.community.reader

import app.bookorbit.community.data.EbookFile
import app.bookorbit.community.ui.preferredReadingFile
import org.junit.Assert.*
import org.junit.Test

class ReaderInteractionTest {
    @Test
    fun `screen edges turn pages and center reveals controls`() {
        assertEquals(ReaderTap.PREVIOUS, readerTap(100f, 1000))
        assertEquals(ReaderTap.CONTROLS, readerTap(250f, 1000))
        assertEquals(ReaderTap.CONTROLS, readerTap(500f, 1000))
        assertEquals(ReaderTap.CONTROLS, readerTap(750f, 1000))
        assertEquals(ReaderTap.NEXT, readerTap(900f, 1000))
        assertEquals(ReaderTap.CONTROLS, readerTap(Float.NaN, 1000))
    }

    @Test
    fun `direct opening respects the chosen edition and falls back to a supported format`() {
        val pdf = EbookFile(101, "pdf", "Book.pdf")
        val epub = EbookFile(102, "epub", "Book.epub")
        val unsupported = EbookFile(103, "azw3", "Book.azw3")
        assertEquals(epub, preferredReadingFile(listOf(pdf, epub, unsupported), null))
        assertEquals(pdf, preferredReadingFile(listOf(pdf, epub), 101))
        assertEquals(epub, preferredReadingFile(listOf(pdf, epub, unsupported), 103))
        assertEquals(pdf, preferredReadingFile(listOf(pdf), 102))
        assertNull(preferredReadingFile(listOf(unsupported), 103))
    }
}

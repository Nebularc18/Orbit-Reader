package app.bookorbit.community.reader

import app.bookorbit.community.data.AnnotationRect
import org.junit.Assert.*
import org.junit.Test

class PdfTextSelectionTest {
    @Test
    fun `selected lines keep exact text and separate highlight boxes`() {
        val first = AnnotationRect(10.0, 20.0, 100.0, 12.0)
        val second = AnnotationRect(10.0, 40.0, 70.0, 12.0)
        val selection =
            pdfTextSelection(
                2,
                600.0,
                800.0,
                listOf("First line" to listOf(first), "Second line" to listOf(second)),
            )!!
        assertEquals("First line\nSecond line", selection.text)
        assertEquals(2, selection.position.page)
        assertEquals(listOf(first, second), selection.position.rects)
        assertEquals(AnnotationRect(10.0, 20.0, 100.0, 32.0), selection.position.rect)
    }

    @Test
    fun `empty text and malformed or excessive geometry do not become highlights`() {
        val box = AnnotationRect(10.0, 20.0, 100.0, 12.0)
        assertNull(pdfTextSelection(0, 600.0, 800.0, listOf(" " to listOf(box))))
        assertNull(
            pdfTextSelection(0, 600.0, 800.0, listOf("text" to listOf(box.copy(x = Double.NaN))))
        )
        assertNull(pdfTextSelection(0, 600.0, 800.0, listOf("text" to List(1001) { box })))
        assertNull(pdfTextSelection(0, 600.0, 800.0, listOf("x".repeat(10001) to listOf(box))))
        assertNull(pdfTextSelection(0, 0.0, 800.0, listOf("text" to listOf(box))))
    }

    @Test
    fun `text selection clips geometry and ignores letterbox taps`() {
        val selection =
            pdfTextSelection(
                0,
                600.0,
                800.0,
                listOf("edge" to listOf(AnnotationRect(-10.0, 790.0, 30.0, 20.0))),
            )!!
        assertEquals(AnnotationRect(0.0, 790.0, 20.0, 10.0), selection.position.rect)
        val fit = PdfPageFit(600.0, 800.0, 1200.0, 1200.0)
        assertNull(fit.point(100.0, 100.0))
        assertEquals(100.0 to 200.0, fit.point(300.0, 300.0))
    }
}

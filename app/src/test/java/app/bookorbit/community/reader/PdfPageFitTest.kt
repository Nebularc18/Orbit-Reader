package app.bookorbit.community.reader

import app.bookorbit.community.data.AnnotationRect
import org.junit.Assert.*
import org.junit.Test

class PdfPageFitTest {
    @Test
    fun `letterboxing maps a reversed drag into PDF page points`() {
        val fit = PdfPageFit(600.0, 800.0, 1200.0, 1200.0)
        assertEquals(150.0, fit.left, 0.001)
        assertEquals(
            AnnotationRect(100.0, 200.0, 200.0, 100.0),
            fit.area(600.0, 450.0, 300.0, 300.0),
        )
    }

    @Test
    fun `selection clips to page and refuses margin taps and zero sizes`() {
        val fit = PdfPageFit(600.0, 800.0, 1200.0, 1200.0)
        assertEquals(AnnotationRect(0.0, 0.0, 600.0, 800.0), fit.area(0.0, 0.0, 1200.0, 1200.0))
        assertNull(fit.area(0.0, 100.0, 120.0, 200.0))
        assertNull(fit.area(400.0, 200.0, 400.0, 200.0))
        assertNull(PdfPageFit(600.0, 800.0, 0.0, 0.0).area(0.0, 0.0, 10.0, 10.0))
    }
}

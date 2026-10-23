package app.bookorbit.community.ui

import org.junit.Assert.*
import org.junit.Test

class AdaptiveLayoutTest {
    @Test
    fun `navigation switches at tablet width and larger fonts reduce grid density`() {
        assertFalse(useNavigationRail(599))
        assertTrue(useNavigationRail(600))
        assertTrue(useNavigationRail(1200))
        assertEquals(100, catalogCellWidth(420, 1f))
        assertEquals(156, catalogCellWidth(840, 1f))
        assertTrue(catalogCellWidth(420, 2f) > catalogCellWidth(420, 1f))
        assertTrue(catalogCellWidth(840, 2f) > catalogCellWidth(840, 1f))
    }
}

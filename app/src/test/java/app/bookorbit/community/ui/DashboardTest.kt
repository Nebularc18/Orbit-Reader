package app.bookorbit.community.ui

import app.bookorbit.community.data.BookCard
import org.junit.Assert.*
import org.junit.Test

class DashboardTest {
    @Test
    fun `remaining time clamps positions before the start and after the end`() {
        assertEquals(5087000L, remainingAudioTime(21797000, 16710000))
        assertEquals(20000L, remainingAudioTime(20000, -100))
        assertEquals(0L, remainingAudioTime(20000, 30000))
        assertEquals(0L, remainingAudioTime(0, 0))
    }

    @Test
    fun `local reading remains first with refreshed server metadata and no duplicates`() {
        val local = listOf(BookCard(2, "Old title"), BookCard(1, "Offline book"))
        val remote =
            listOf(BookCard(3, "Other device"), BookCard(2, "New title", coverVersion = "v2"))
        val merged = mergeDashboardBooks(local, remote)
        assertEquals(listOf(2, 1, 3), merged.map { it.id })
        assertEquals("New title", merged.first().title)
        assertEquals("v2", merged.first().coverVersion)
        assertEquals(10, mergeDashboardBooks(emptyList(), (1..100).map { BookCard(it) }).size)
    }
}

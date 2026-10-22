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

    @Test
    fun `successful listening refresh removes local books absent from the server shelf`() {
        val local = listOf(BookCard(1, "Completed on web"), BookCard(2, "Still listening"))
        val remote = listOf(BookCard(2, "Updated title"), BookCard(3, "Started on web"))
        val merged = mergeDashboardBooks(local, remote, serverAuthoritative = true)
        assertEquals(listOf(2, 3), merged.map { it.id })
        assertEquals("Updated title", merged.first().title)
    }

    @Test
    fun `an empty listening shelf is not mistaken for an offline request`() {
        assertTrue(
            mergeDashboardBooks(listOf(BookCard(1)), emptyList(), serverAuthoritative = true)
                .isEmpty()
        )
    }

    @Test
    fun `failed listening refresh retains local offline history`() {
        assertEquals(
            listOf(1),
            mergeDashboardBooks(listOf(BookCard(1)), null, serverAuthoritative = true).map { it.id },
        )
    }
}

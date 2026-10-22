package app.bookorbit.community.data

import androidx.paging.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LibraryBrowsingTest {
    @Test
    fun `group pagination handles final page and cancellation`() = runBlocking {
        val source = GroupPagingSource { page ->
            LibraryGroupsPage(
                (1..minOf(30, 65 - (page - 1) * 30)).map {
                    LibraryGroup((page - 1) * 30 + it, "Series $it")
                },
                65,
                page - 1,
                30,
            )
        }
        val first =
            source.load(PagingSource.LoadParams.Refresh(null, 30, false))
                as PagingSource.LoadResult.Page
        assertEquals(30, first.data.size)
        assertNull(first.prevKey)
        assertEquals(2, first.nextKey)
        val last =
            source.load(PagingSource.LoadParams.Append(3, 30, false))
                as PagingSource.LoadResult.Page
        assertEquals(5, last.data.size)
        assertEquals(61, last.data.first().id)
        assertNull(last.nextKey)
        val canceled = GroupPagingSource { throw CancellationException() }
        try {
            canceled.load(PagingSource.LoadParams.Refresh(null, 30, false))
            fail("Expected cancellation")
        } catch (_: CancellationException) {}
    }

    @Test
    fun `failed group page retries without losing page identity`() = runBlocking {
        var fail = true
        val source = GroupPagingSource { page ->
            if (fail) throw java.io.IOException("offline")
            else LibraryGroupsPage(listOf(LibraryGroup(page, "Shelf")), 31, page - 1, 30)
        }
        assertTrue(
            source.load(PagingSource.LoadParams.Append(2, 30, false))
                is PagingSource.LoadResult.Error
        )
        fail = false
        val page =
            source.load(PagingSource.LoadParams.Append(2, 30, false))
                as PagingSource.LoadResult.Page
        assertEquals(2, page.data.single().id)
        assertEquals(1, page.prevKey)
        assertNull(page.nextKey)
    }

    @Test
    fun `activity durations preserve hours and clamp invalid totals`() {
        assertEquals("0 min", activityDuration(-1))
        assertEquals("<1 min", activityDuration(59))
        assertEquals("1 min", activityDuration(60))
        assertEquals("1 hr", activityDuration(3600))
        assertEquals("25 hr 1 min", activityDuration(90060))
    }
}

package app.bookorbit.community.data

import androidx.paging.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BookPagingSourceTest {
    @Test
    fun `loads subsequent bounded pages and stops at the server total`() = runBlocking {
        val requested = mutableListOf<Int>()
        val source = BookPagingSource { page ->
            requested += page
            BooksPage(
                ((page - 1) * 30 + 1..minOf(page * 30, 65)).map { BookCard(it) },
                65,
                page - 1,
                30,
            )
        }
        val first =
            source.load(PagingSource.LoadParams.Refresh(null, 30, false))
                as PagingSource.LoadResult.Page<Int, BookCard>
        assertEquals(30, first.data.size)
        assertNull(first.prevKey)
        assertEquals(2, first.nextKey)
        val last =
            source.load(PagingSource.LoadParams.Append(3, 30, false))
                as PagingSource.LoadResult.Page<Int, BookCard>
        assertEquals(listOf(61, 62, 63, 64, 65), last.data.map { it.id })
        assertEquals(2, last.prevKey)
        assertNull(last.nextKey)
        assertEquals(listOf(1, 3), requested)
    }

    @Test
    fun `unknown local totals stop on short pages and failed pages can retry`() = runBlocking {
        var shouldFail = true
        val source = BookPagingSource { page ->
            if (shouldFail) throw java.io.IOException("offline")
            BooksPage(listOf(BookCard(page)), -1, page, 30)
        }
        assertTrue(
            source.load(PagingSource.LoadParams.Append(2, 30, false))
                is PagingSource.LoadResult.Error
        )
        shouldFail = false
        val retry =
            source.load(PagingSource.LoadParams.Append(2, 30, false))
                as PagingSource.LoadResult.Page<Int, BookCard>
        assertEquals(2, retry.data.single().id)
        assertNull(retry.nextKey)
        assertEquals(1, retry.prevKey)
    }

    @Test
    fun `refresh uses the viewport page after earlier pages are dropped`() {
        val source = BookPagingSource { error("not used") }
        val state =
            PagingState(
                listOf(PagingSource.LoadResult.Page<Int, BookCard>(listOf(BookCard(91)), 3, 5)),
                0,
                PagingConfig(30),
                0,
            )
        assertEquals(4, source.getRefreshKey(state))
    }

    @Test
    fun `cancellation propagates instead of becoming a retry error`() = runBlocking {
        val source = BookPagingSource { throw CancellationException() }
        try {
            source.load(PagingSource.LoadParams.Refresh(null, 30, false))
            fail("Expected cancellation")
        } catch (_: CancellationException) {}
    }
}

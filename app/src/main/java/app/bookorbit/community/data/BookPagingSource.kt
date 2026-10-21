package app.bookorbit.community.data

import androidx.paging.*
import kotlinx.coroutines.CancellationException

const val BOOK_PAGE_SIZE = 30
const val BOOK_WINDOW_SIZE = 150

class BookPagingSource(private val fetch: suspend (Int) -> BooksPage) :
    PagingSource<Int, BookCard>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, BookCard> {
        val page = params.key ?: 1
        return try {
            val result = fetch(page)
            LoadResult.Page(
                data = result.items.distinctBy { it.id },
                prevKey = if (page > 1) page - 1 else null,
                nextKey =
                    if (
                        result.items.isEmpty() ||
                            (result.total >= 0 && page.toLong() * BOOK_PAGE_SIZE >= result.total) ||
                            (result.total < 0 && result.items.size < BOOK_PAGE_SIZE)
                    )
                        null
                    else page + 1,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            LoadResult.Error(error)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, BookCard>): Int? =
        state.anchorPosition?.let {
            state.closestPageToPosition(it)?.let { page ->
                page.prevKey?.plus(1) ?: page.nextKey?.minus(1)
            }
        }
}

fun bookPager(fetch: suspend (Int) -> BooksPage) =
    Pager(
        config =
            PagingConfig(
                pageSize = BOOK_PAGE_SIZE,
                initialLoadSize = BOOK_PAGE_SIZE,
                prefetchDistance = 8,
                enablePlaceholders = false,
                maxSize = BOOK_WINDOW_SIZE,
            ),
        pagingSourceFactory = { BookPagingSource(fetch) },
    )

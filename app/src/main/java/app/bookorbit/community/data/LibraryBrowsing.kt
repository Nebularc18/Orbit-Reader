package app.bookorbit.community.data

import androidx.paging.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
enum class LibraryGroupKind(val label: String, val path: String) {
    SERIES("Series", "series"),
    AUTHORS("Authors", "authors"),
    COLLECTIONS("Collections", "collections"),
    SHELVES("Shelves", "smart-scopes"),
}

@Serializable
data class LibraryGroup(
    val id: Int,
    val name: String,
    val bookCount: Int? = null,
    val readCount: Int? = null,
    val authors: List<String> = emptyList(),
    val coverBookIds: List<Int> = emptyList(),
    val coverBookId: Int? = null,
    val description: String? = null,
    val mediaType: String = "books",
) {
    val coverId: Int?
        get() = coverBookIds.firstOrNull() ?: coverBookId
}

@Serializable
data class LibraryGroupsPage(
    val items: List<LibraryGroup>,
    val total: Int,
    val page: Int,
    val size: Int,
)

enum class GroupOrder(val label: String, val field: String, val direction: String) {
    NAME("Name A-Z", "name", "asc"),
    NAME_DESC("Name Z-A", "name", "desc"),
    MOST_BOOKS("Most books", "bookCount", "desc"),
    RECENT("Recently added", "lastAddedAt", "desc"),
}

class GroupPagingSource(private val fetch: suspend (Int) -> LibraryGroupsPage) :
    PagingSource<Int, LibraryGroup>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, LibraryGroup> {
        val page = params.key ?: 1
        return try {
            val result = fetch(page)
            LoadResult.Page(
                result.items.distinctBy { it.id },
                if (page > 1) page - 1 else null,
                if (result.items.isEmpty() || page.toLong() * BOOK_PAGE_SIZE >= result.total) null
                else page + 1,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, LibraryGroup>): Int? =
        state.anchorPosition?.let {
            state.closestPageToPosition(it)?.let { page ->
                page.prevKey?.plus(1) ?: page.nextKey?.minus(1)
            }
        }
}

fun groupPager(fetch: suspend (Int) -> LibraryGroupsPage) =
    Pager(
        PagingConfig(
            pageSize = BOOK_PAGE_SIZE,
            initialLoadSize = BOOK_PAGE_SIZE,
            prefetchDistance = 8,
            enablePlaceholders = false,
            maxSize = BOOK_WINDOW_SIZE,
        ),
        pagingSourceFactory = { GroupPagingSource(fetch) },
    )

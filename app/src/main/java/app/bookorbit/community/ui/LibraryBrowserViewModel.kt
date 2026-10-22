package app.bookorbit.community.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class LibraryBrowserState(
    val kind: LibraryGroupKind = LibraryGroupKind.SERIES,
    val query: String = "",
    val order: GroupOrder = GroupOrder.NAME,
    val group: LibraryGroup? = null,
    val book: EbookDetail? = null,
    val opening: Boolean = false,
    val error: String? = null,
)

class LibraryBrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OrbitApplication
    val owner = app.sessions.read()?.scope.orEmpty()
    private val mutable = MutableStateFlow(LibraryBrowserState())
    val state = mutable.asStateFlow()
    private var selection: Job? = null
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val groups =
        mutable
            .map { Triple(it.kind, it.query, it.order) }
            .distinctUntilChanged()
            .debounce(300)
            .flatMapLatest { (kind, query, order) ->
                groupPager { page ->
                        withContext(Dispatchers.IO) {
                            app.api.libraryGroups(kind, page, query, order, owner)
                        }
                    }
                    .flow
            }
            .cachedIn(viewModelScope)
    @OptIn(ExperimentalCoroutinesApi::class)
    val books =
        mutable
            .map { it.kind to it.group?.id }
            .distinctUntilChanged()
            .flatMapLatest { (kind, id) ->
                if (id == null) flowOf(PagingData.empty())
                else
                    bookPager { page ->
                            withContext(Dispatchers.IO) {
                                app.api.libraryGroupBooks(kind, id, page, owner)
                            }
                        }
                        .flow
            }
            .cachedIn(viewModelScope)

    fun kind(value: LibraryGroupKind) {
        mutable.update {
            it.copy(kind = value, query = "", group = null, error = null, order = GroupOrder.NAME)
        }
    }

    fun query(value: String) {
        mutable.update { it.copy(query = value.take(500)) }
    }

    fun order(value: GroupOrder) {
        mutable.update { it.copy(order = value) }
    }

    fun group(value: LibraryGroup?) {
        selection?.cancel()
        mutable.update { it.copy(group = value, book = null, opening = false, error = null) }
    }

    fun closeBook() {
        selection?.cancel()
        mutable.update { it.copy(book = null, opening = false, error = null) }
    }

    fun book(id: Int) {
        selection?.cancel()
        selection =
            viewModelScope.launch {
                mutable.update { it.copy(opening = true, error = null) }
                try {
                    val detail = withContext(Dispatchers.IO) { app.api.ebookDetail(id, owner) }
                    if (app.sessions.read()?.scope == owner)
                        mutable.update { it.copy(book = detail, opening = false) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutable.update {
                        it.copy(
                            opening = false,
                            error = e.message ?: "Could not open this book. Try again.",
                        )
                    }
                }
            }
    }
}

package app.bookorbit.community.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class BookRequestsState(
    val page: Int = 1,
    val status: String? = null,
    val media: String? = null,
    val items: List<BookRequest> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val submitted: Int = 0,
)

class BookRequestsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OrbitApplication
    private val owner = app.sessions.read()?.scope.orEmpty()
    private val mutable = MutableStateFlow(BookRequestsState())
    val state = mutable.asStateFlow()
    private var load: Job? = null

    fun filters(status: String?, media: String?) {
        if (mutable.value.busy) return
        mutable.update { it.copy(status = status, media = media, page = 1) }
        refresh()
    }

    fun page(delta: Int) {
        if (mutable.value.loading || mutable.value.busy) return
        mutable.update { it.copy(page = (it.page + delta).coerceAtLeast(1)) }
        refresh()
    }

    fun refresh() {
        if (mutable.value.busy) return
        load?.cancel()
        val request = mutable.value
        mutable.update { it.copy(loading = true, items = emptyList(), error = null) }
        load =
            viewModelScope.launch {
                try {
                    val result =
                        withContext(Dispatchers.IO) {
                            app.api.bookRequests(request.page, request.status, request.media, owner)
                        }
                    check(app.sessions.read()?.scope == owner)
                    mutable.update {
                        it.copy(items = result.items, total = result.total, loading = false)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutable.update { it.copy(loading = false, error = requestError(e)) }
                }
            }
    }

    fun submit(value: NewBookRequest) =
        mutate(true) {
            val result = app.api.submitBookRequest(value, owner)
            if (result.subscribed) "Joined the existing request for ${result.request.title}."
            else "Requested ${result.request.title}."
        }

    fun cancel(value: BookRequest) =
        mutate(false) {
            val account =
                app.sessions.read()?.takeIf { it.scope == owner }?.user ?: throw ApiFailure(401)
            check(value.canCancel(account))
            app.api.cancelBookRequest(value.id, owner)
            "Request cancelled."
        }

    private fun mutate(submit: Boolean, action: () -> String) {
        if (mutable.value.busy) return
        load?.cancel()
        mutable.update { it.copy(busy = true, loading = false, error = null, message = null) }
        viewModelScope.launch {
            try {
                val message =
                    withContext(Dispatchers.IO) {
                        check(app.sessions.read()?.scope == owner)
                        action()
                    }
                check(app.sessions.read()?.scope == owner)
                mutable.update {
                    it.copy(
                        busy = false,
                        message = message,
                        page = if (submit) 1 else it.page,
                        status = if (submit) null else it.status,
                        media = if (submit) null else it.media,
                        submitted = it.submitted + if (submit) 1 else 0,
                    )
                }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update {
                    it.copy(
                        busy = false,
                        error =
                            if (e is ApiFailure && e.status < 500) requestError(e)
                            else
                                "The result is uncertain. Close this form and refresh your requests before trying again.",
                    )
                }
            }
        }
    }
}

fun requestError(error: Exception): String =
    when ((error as? ApiFailure)?.status) {
        403 -> "Your account does not have permission to use book requests."
        404,
        405,
        501 -> "Book requests are unavailable on this server. Check its version and configuration."
        400,
        409,
        422 ->
            "The server refused this request. Refresh the list and check the title, account limits, and request status before trying again."
        401 -> "Your session expired. Sign in again."
        else -> "Could not load or update book requests. Check your connection and try again."
    }

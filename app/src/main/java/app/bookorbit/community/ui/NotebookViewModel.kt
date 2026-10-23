package app.bookorbit.community.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.*
import app.bookorbit.community.reader.EpubReaderActivity
import app.bookorbit.community.reader.PageReaderActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class NotebookState(
    val query: String = "",
    val filter: NotebookFilter = NotebookFilter.ALL,
    val page: Int = 1,
    val result: NotebookPage = NotebookPage(emptyList(), false, 0),
    val loading: Boolean = true,
    val error: String? = null,
)

class NotebookViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OrbitApplication
    private val owner = app.sessions.read()?.scope.orEmpty()
    private val storage = NotebookStorage(app, owner)
    private val mutable = MutableStateFlow(NotebookState())
    val state = mutable.asStateFlow()
    private var job: Job? = null

    fun search(query: String) {
        mutable.update { it.copy(query = query.take(300), page = 1) }
        refresh(debounce = true)
    }

    fun filter(value: NotebookFilter) {
        mutable.update { it.copy(filter = value, page = 1) }
        refresh()
    }

    fun page(delta: Int) {
        mutable.update { it.copy(page = (it.page + delta).coerceAtLeast(1)) }
        refresh()
    }

    fun refresh(debounce: Boolean = false) {
        job?.cancel()
        val request = mutable.value
        mutable.update {
            it.copy(loading = true, error = null, result = NotebookPage(emptyList(), false, 0))
        }
        job =
            viewModelScope.launch {
                try {
                    if (debounce) delay(300)
                    val result =
                        withContext(Dispatchers.IO) {
                            check(app.sessions.read()?.scope == owner)
                            storage.page(request.query, request.filter, request.page) {
                                ensureActive()
                            }
                        }
                    check(app.sessions.read()?.scope == owner)
                    mutable.update { it.copy(result = result, loading = false) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    mutable.update {
                        it.copy(
                            loading = false,
                            error = "Could not load Notebook. Refresh and try again.",
                        )
                    }
                }
            }
    }

    fun save(entry: NotebookEntry, title: String, note: String = "") = change {
        storage.save(entry, title, note)
    }

    fun delete(entry: NotebookEntry) = change { storage.delete(entry) }

    private fun change(action: () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(app.sessions.read()?.scope == owner)
                    action()
                }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutable.update {
                    it.copy(error = "Could not update this entry. Refresh Notebook and try again.")
                }
            }
        }
    }

    fun open(entry: NotebookEntry) {
        viewModelScope.launch {
            try {
                val book =
                    withContext(Dispatchers.IO) {
                        check(app.sessions.read()?.scope == owner)
                        storage.readingTarget(entry)
                    }
                check(app.sessions.read()?.scope == owner)
                app.startActivity(
                    Intent(
                            app,
                            if (book.file.format?.lowercase() in listOf("pdf", "cbz", "cbr", "cb7"))
                                PageReaderActivity::class.java
                            else EpubReaderActivity::class.java,
                        )
                        .apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra("scope", owner)
                            putExtra("fileId", book.file.id)
                            putExtra("noteId", entry.reading!!.id)
                        }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutable.update {
                    it.copy(
                        error =
                            "Could not open this saved location. Refresh Notebook or download the matching file from Read."
                    )
                }
            }
        }
    }
}

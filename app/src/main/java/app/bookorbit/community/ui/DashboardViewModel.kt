package app.bookorbit.community.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.*
import app.bookorbit.community.reader.EbookStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DashboardSection(val title: String, val endpoint: String, val empty: String) {
    LISTENING(
        "Continue listening",
        "continue-listening",
        "Start an audiobook and return to it here.",
    ),
    READING("Continue reading", "continue-reading", "Start an ebook and return to it here."),
    SERIES(
        "Next in series",
        "up-next-in-series",
        "Your server will suggest the next books in your series here.",
    ),
}

data class DashboardRow(
    val section: DashboardSection,
    val books: List<BookCard>,
    val error: Boolean,
)

data class DashboardState(val loading: Boolean = true, val rows: List<DashboardRow> = emptyList())

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OrbitApplication
    val owner = app.sessions.read()?.scope.orEmpty()
    private val mutable = MutableStateFlow(DashboardState())
    val state = mutable.asStateFlow()
    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        job =
            viewModelScope.launch {
                mutable.value = mutable.value.copy(loading = true)
                val rows =
                    withContext(Dispatchers.IO) {
                        if (app.sessions.read()?.scope != owner) return@withContext emptyList()
                        DashboardSection.entries
                            .map { section ->
                                async {
                                    val local =
                                        when (section) {
                                            DashboardSection.LISTENING ->
                                                AudioStorage(app, owner).recentlyPlayed().map {
                                                    BookCard(
                                                        it.book.id,
                                                        it.book.title,
                                                        it.book.authors,
                                                    )
                                                }
                                            DashboardSection.READING ->
                                                EbookStorage(app, owner).recentlyRead().map {
                                                    BookCard(
                                                        it.bookId,
                                                        it.title,
                                                        it.authors,
                                                        files = listOf(it.file),
                                                    )
                                                }
                                            DashboardSection.SERIES -> emptyList()
                                        }
                                    val remote =
                                        try {
                                            app.api.dashboard(section.endpoint, owner)
                                        } catch (error: CancellationException) {
                                            throw error
                                        } catch (_: Exception) {
                                            null
                                        }
                                    DashboardRow(
                                        section,
                                        mergeDashboardBooks(
                                            local,
                                            remote?.books.orEmpty().filter {
                                                section != DashboardSection.READING ||
                                                    it.files.any { file -> file.supported }
                                            },
                                        ),
                                        remote == null,
                                    )
                                }
                            }
                            .awaitAll()
                    }
                if (app.sessions.read()?.scope == owner) mutable.value = DashboardState(false, rows)
            }
    }
}

fun mergeDashboardBooks(local: List<BookCard>, remote: List<BookCard>): List<BookCard> {
    val details = remote.associateBy { it.id }
    return (local.map { details[it.id] ?: it } + remote).distinctBy { it.id }.take(10)
}

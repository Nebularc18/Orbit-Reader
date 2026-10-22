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

data class DashboardState(
    val loading: Boolean = true,
    val rows: List<DashboardRow> = emptyList(),
    val goal: ReadingGoal? = null,
    val streak: ReadingStreak? = null,
)

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
                val goal = async(Dispatchers.IO) { optionalWidget { app.api.readingGoal(owner) } }
                val streak =
                    async(Dispatchers.IO) { optionalWidget { app.api.readingStreak(owner) } }
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
                                            remote?.books?.filter {
                                                section != DashboardSection.READING ||
                                                    it.files.any { file -> file.supported }
                                            },
                                            serverAuthoritative =
                                                section == DashboardSection.LISTENING,
                                        ),
                                        remote == null,
                                    )
                                }
                            }
                            .awaitAll()
                    }
                val goalValue = goal.await()
                val streakValue = streak.await()
                if (app.sessions.read()?.scope == owner)
                    mutable.value = DashboardState(false, rows, goalValue, streakValue)
            }
    }
}

fun mergeDashboardBooks(
    local: List<BookCard>,
    remote: List<BookCard>?,
    serverAuthoritative: Boolean = false,
): List<BookCard> {
    // An empty successful shelf can mean the last book was completed on another device.
    if (serverAuthoritative && remote != null) return remote.distinctBy { it.id }.take(10)
    val details = remote.orEmpty().associateBy { it.id }
    return (local.map { details[it.id] ?: it } + remote.orEmpty()).distinctBy { it.id }.take(10)
}

private fun <T> optionalWidget(load: () -> T): T? =
    try {
        load()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

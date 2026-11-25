package app.bookorbit.community.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.ApiFailure
import app.bookorbit.community.data.ReadingHistoryPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReadingHistoryState(
    val years: List<Int> = emptyList(),
    val results: ReadingHistoryPage? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

class ReadingHistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OrbitApplication
    private val owner = app.sessions.read()?.scope.orEmpty()
    private val mutable = MutableStateFlow(ReadingHistoryState())
    val state = mutable.asStateFlow()
    private var load: Job? = null

    private var summaryLoad: Job? = null

    fun refreshYears() {
        if (summaryLoad?.isActive == true) return
        summaryLoad =
            viewModelScope.launch {
                try {
                    val summary =
                        withContext(Dispatchers.IO) { app.api.readingHistorySummary(owner) }
                    if (app.sessions.read()?.scope == owner)
                        mutable.update {
                            it.copy(years = summary.years.map { year -> year.year }.distinct())
                        }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // All-time history remains available if the year summary cannot be loaded.
                }
            }
    }

    fun refresh(query: String, year: Int?, page: Int) {
        load?.cancel()
        mutable.update { it.copy(loading = true, error = null, results = null) }
        load =
            viewModelScope.launch {
                delay(250)
                try {
                    val result =
                        withContext(Dispatchers.IO) {
                            app.api.readingHistory(owner, query, year, page)
                        }
                    if (app.sessions.read()?.scope == owner)
                        mutable.update { it.copy(loading = false, results = result) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val message =
                        when ((error as? ApiFailure)?.status) {
                            401 -> "Sign in again to see your reading history."
                            404 -> "Reading history is unavailable on this server."
                            else -> "Could not load reading history."
                        }
                    mutable.update { it.copy(loading = false, error = message) }
                }
            }
    }
}

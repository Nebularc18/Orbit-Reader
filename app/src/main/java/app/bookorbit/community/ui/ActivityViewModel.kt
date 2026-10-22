package app.bookorbit.community.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.*
import java.time.LocalDate
import java.time.temporal.WeekFields
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ReadingActivityState(
    val overview: ActivityOverview? = null,
    val timeline: ActivityTimeline? = null,
    val sources: ActivitySources? = null,
    val week: LocalDate = LocalDate.now().with(java.time.DayOfWeek.MONDAY),
    val loading: Boolean = false,
    val error: String? = null,
    val historyError: String? = null,
)

class ActivityViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OrbitApplication
    val owner = app.sessions.read()?.scope.orEmpty()
    private val mutable = MutableStateFlow(ReadingActivityState())
    val state = mutable.asStateFlow()
    private var job: Job? = null

    fun week(delta: Long) {
        val next = mutable.value.week.plusWeeks(delta)
        if (next.isAfter(LocalDate.now()) || next.year < 1970 || next.year > 2100) return
        mutable.update { it.copy(week = next, timeline = null) }
        refresh()
    }

    fun refresh() {
        job?.cancel()
        job =
            viewModelScope.launch {
                mutable.update { it.copy(loading = true, error = null, historyError = null) }
                val week = mutable.value.week
                val overview =
                    async(Dispatchers.IO) { activityResult { app.api.activityOverview(owner) } }
                val sources =
                    async(Dispatchers.IO) { activityResult { app.api.activitySources(owner) } }
                val history =
                    async(Dispatchers.IO) {
                        activityResult {
                            app.api.activityTimeline(
                                week.get(WeekFields.ISO.weekBasedYear()),
                                week.get(WeekFields.ISO.weekOfWeekBasedYear()),
                                owner,
                            )
                        }
                    }
                val o = overview.await()
                val s = sources.await()
                val h = history.await()
                if (app.sessions.read()?.scope == owner)
                    mutable.update {
                        it.copy(
                            overview = o.getOrNull() ?: it.overview,
                            sources = s.getOrNull(),
                            timeline = h.getOrNull(),
                            loading = false,
                            error = o.exceptionOrNull()?.let(::activityError),
                            historyError = h.exceptionOrNull()?.let(::activityError),
                        )
                    }
            }
    }
}

private suspend fun <T> activityResult(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

private fun activityError(error: Throwable): String =
    if (error is ApiFailure && error.status == 404)
        "This activity view is not available on your server version."
    else "Could not refresh activity. Check your connection and try again."

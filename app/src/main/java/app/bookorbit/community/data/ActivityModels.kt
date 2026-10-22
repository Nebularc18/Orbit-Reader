package app.bookorbit.community.data

import kotlinx.serialization.Serializable

@Serializable
data class ActivityDuration(
    val totalSeconds: Long = 0,
    val readingSeconds: Long = 0,
    val listeningSeconds: Long = 0,
)

@Serializable
data class ActivitySnapshot(
    val today: ActivityDuration,
    val lastSevenDays: ActivityDuration,
    val previousSevenDays: ActivityDuration,
    val currentStreak: Int,
    val longestStreak: Int,
    val completedBooksYtd: Int,
)

@Serializable
data class ActivityDay(
    val day: String,
    val totalSeconds: Long = 0,
    val readingSeconds: Long = 0,
    val listeningSeconds: Long = 0,
    val sessionsCount: Int = 0,
)

@Serializable data class ActivityDays(val days: List<ActivityDay> = emptyList())

@Serializable
data class ActivityGoal(
    val year: Int,
    val goalBooks: Int? = null,
    val completedBooks: Int = 0,
    val projectedBooks: Double? = null,
    val status: String? = null,
)

@Serializable data class ActivityMonth(val year: Int, val month: Int, val count: Int)

@Serializable data class ActivityCompletion(val months: List<ActivityMonth> = emptyList())

@Serializable
data class ActivityOverview(
    val timezone: String,
    val snapshot: ActivitySnapshot,
    val dailyActivity: ActivityDays,
    val goal: ActivityGoal,
    val completion: ActivityCompletion = ActivityCompletion(),
)

@Serializable
data class ActivitySession(
    val sessionId: Int,
    val bookId: Int,
    val bookTitle: String? = null,
    val bookFormat: String? = null,
    val bookSource: String,
    val startedAt: String,
    val endedAt: String,
    val durationSeconds: Long,
)

@Serializable
data class ActivityTimeline(
    val year: Int,
    val week: Int,
    val weekStart: String,
    val weekEnd: String,
    val items: List<ActivitySession> = emptyList(),
)

@Serializable data class ActivitySource(val bucket: String, val readingSeconds: Long)

@Serializable
data class ActivitySources(val totalSeconds: Long, val slices: List<ActivitySource> = emptyList())

fun activityDuration(seconds: Long): String {
    val minutes = seconds.coerceAtLeast(0) / 60
    return when {
        seconds <= 0 -> "0 min"
        minutes == 0L -> "<1 min"
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0L -> "${minutes / 60} hr"
        else -> "${minutes / 60} hr ${minutes % 60} min"
    }
}

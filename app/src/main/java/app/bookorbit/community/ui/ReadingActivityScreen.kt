package app.bookorbit.community.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.bookorbit.community.data.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun ReadingActivityScreen(account: Session, modifier: Modifier = Modifier, onBack: () -> Unit) {
    val model: ActivityViewModel = viewModel(key = "activity-${account.scope}")
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model) { model.refresh() }
    val overview = state.overview
    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            "Your activity",
            "Reading & listening across your devices",
            onBack,
            model::refresh,
        )
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.error?.let { error -> item { LibraryErrorState(error, model::refresh) } }
            if (overview != null) {
                item {
                    ActivityPanel("This week") {
                        val total = overview.snapshot.lastSevenDays
                        Text(
                            activityDuration(total.totalSeconds),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "Over the last 7 days",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            ActivityMetric(
                                "Reading",
                                activityDuration(total.readingSeconds),
                                Modifier.weight(1f),
                            )
                            ActivityMetric(
                                "Listening",
                                activityDuration(total.listeningSeconds),
                                Modifier.weight(1f),
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            ActivityMetric(
                                "Today",
                                activityDuration(overview.snapshot.today.totalSeconds),
                                Modifier.weight(1f),
                            )
                            ActivityMetric(
                                "Previous 7 days",
                                activityDuration(overview.snapshot.previousSevenDays.totalSeconds),
                                Modifier.weight(1f),
                            )
                        }
                    }
                }
                item {
                    ActivityPanel("Your momentum") {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            ActivityMetric(
                                "Current streak",
                                "${overview.snapshot.currentStreak} days",
                                Modifier.weight(1f),
                            )
                            ActivityMetric(
                                "Longest streak",
                                "${overview.snapshot.longestStreak} days",
                                Modifier.weight(1f),
                            )
                        }
                        val goal = overview.goal
                        Text(
                            if (goal.goalBooks != null)
                                "${goal.completedBooks} of ${goal.goalBooks} books in ${goal.year}"
                            else "${goal.completedBooks} books finished in ${goal.year}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        goal.goalBooks
                            ?.takeIf { it > 0 }
                            ?.let { target ->
                                LinearProgressIndicator(
                                    progress = {
                                        (goal.completedBooks.toFloat() / target).coerceIn(0f, 1f)
                                    },
                                    Modifier.fillMaxWidth().height(6.dp),
                                )
                                Text(
                                    when (goal.status) {
                                        "ahead" -> "Ahead of your goal"
                                        "on_pace" -> "On pace for your goal"
                                        "behind" -> "Behind your goal pace"
                                        else -> "Your annual reading goal"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                    }
                }
                item {
                    ActivityPanel("Daily activity") {
                        val days = overview.dailyActivity.days.takeLast(28)
                        if (days.isEmpty())
                            Text(
                                "No activity recorded yet.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        else {
                            Text(
                                "${days.first().day} to ${days.last().day}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val max = days.maxOf { it.totalSeconds }.coerceAtLeast(1)
                            Row(
                                Modifier.fillMaxWidth().height(90.dp),
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                days.forEach { day ->
                                    Surface(
                                        Modifier.weight(1f)
                                            .semantics {
                                                contentDescription =
                                                    day.day +
                                                        ", " +
                                                        activityDuration(day.totalSeconds)
                                            }
                                            .fillMaxHeight(
                                                (day.totalSeconds.toFloat() / max).coerceIn(
                                                    .04f,
                                                    1f,
                                                )
                                            ),
                                        shape = RoundedCornerShape(3.dp),
                                        color =
                                            if (day.totalSeconds > 0)
                                                MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                                    ) {}
                                }
                            }
                            Text(
                                "${days.count { it.totalSeconds > 0 }} active days · ${activityDuration(days.sumOf { it.totalSeconds })}",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
                if (overview.completion.months.isNotEmpty())
                    item {
                        ActivityPanel("Books finished") {
                            overview.completion.months.takeLast(6).forEach { month ->
                                Row(Modifier.fillMaxWidth()) {
                                    Text(
                                        "${java.time.Month.of(month.month.coerceIn(1, 12)).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())} ${month.year}",
                                        Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        month.count.toString(),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
            }
            state.sources?.let { sources ->
                item {
                    ActivityPanel("Where you read") {
                        Text(
                            "Last 28 days",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (sources.slices.isEmpty()) Text("No source activity recorded yet.")
                        sources.slices
                            .filter { it.readingSeconds > 0 }
                            .sortedByDescending { it.readingSeconds }
                            .forEach { source ->
                                Row(Modifier.fillMaxWidth()) {
                                    Text(
                                        sourceLabel(source.bucket),
                                        Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        activityDuration(source.readingSeconds),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = {
                                        (source.readingSeconds.toFloat() /
                                                sources.totalSeconds.coerceAtLeast(1))
                                            .coerceIn(0f, 1f)
                                    },
                                    Modifier.fillMaxWidth().height(3.dp),
                                )
                            }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Session history", style = MaterialTheme.typography.titleMedium)
                        Text(
                            state.week.format(DateTimeFormatter.ofPattern("d MMM yyyy")) +
                                " - " +
                                state.week.plusDays(6).format(DateTimeFormatter.ofPattern("d MMM")),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { model.week(-1) }, enabled = !state.loading) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Previous week")
                    }
                    IconButton(
                        onClick = { model.week(1) },
                        enabled = !state.loading && state.week.plusWeeks(1) <= LocalDate.now(),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Next week")
                    }
                }
            }
            state.historyError?.let { error -> item { LibraryErrorState(error, model::refresh) } }
            state.timeline?.let { history ->
                if (history.items.isEmpty())
                    item {
                        LibraryEmptyState(
                            "A quiet week",
                            "No reading or listening sessions were recorded for this week.",
                        )
                    }
                items(history.items, key = { it.sessionId }) { session ->
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (
                                    session.bookFormat?.lowercase() in
                                        setOf("m4b", "mp3", "m4a", "opus", "ogg", "flac")
                                )
                                    Icons.Rounded.Headphones
                                else Icons.Rounded.AutoStories,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    session.bookTitle ?: "Untitled",
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    session.startedAt.take(10) +
                                        " · " +
                                        sourceLabel(session.bookSource),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                activityDuration(session.durationSeconds),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun sourceLabel(value: String): String =
    when (value) {
        "bookorbit" -> "BookOrbit"
        "ios" -> "iOS app"
        "koreader" -> "KOReader"
        "kobo" -> "Kobo"
        "android" -> "Android app"
        "watchos" -> "Apple Watch"
        else -> value.replace('_', ' ').replaceFirstChar(Char::uppercase)
    }

@Composable
private fun ActivityPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ActivityMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

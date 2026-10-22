package app.bookorbit.community.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bookorbit.community.data.ReadingGoal
import app.bookorbit.community.data.ReadingStreak

@Composable
fun ReadingActivityCard(goal: ReadingGoal?, streak: ReadingStreak?, modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .7f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .2f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ShowChart,
                    null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text("Your reading", style = MaterialTheme.typography.titleSmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                streak?.let {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                Icons.Rounded.LocalFireDepartment,
                                null,
                                Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                it.currentStreak.coerceAtLeast(0).toString(),
                                style = MaterialTheme.typography.headlineSmall,
                            )
                        }
                        Text(
                            "day streak",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            it.lastSevenDays.takeLast(7).forEach { active ->
                                Box(
                                    Modifier.size(6.dp)
                                        .background(
                                            if (active) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outlineVariant,
                                            CircleShape,
                                        )
                                )
                            }
                        }
                    }
                }
                goal?.let {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            it.goalBooks
                                ?.takeIf { count -> count > 0 }
                                ?.let { target ->
                                    val accent = MaterialTheme.colorScheme.primary
                                    val track = MaterialTheme.colorScheme.outlineVariant
                                    Canvas(Modifier.size(27.dp)) {
                                        drawArc(
                                            track,
                                            -90f,
                                            360f,
                                            false,
                                            style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
                                        )
                                        drawArc(
                                            accent,
                                            -90f,
                                            (it.completedBooks.toFloat() / target).coerceIn(
                                                0f,
                                                1f,
                                            ) * 360f,
                                            false,
                                            style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
                                        )
                                    }
                                }
                            Text(
                                it.completedBooks.coerceAtLeast(0).toString(),
                                style = MaterialTheme.typography.headlineSmall,
                            )
                        }
                        Text(
                            it.goalBooks
                                ?.takeIf { count -> count > 0 }
                                ?.let { target -> "of $target book goal" } ?: "books finished",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            it.year.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

package app.bookorbit.community.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackTimeline(
    label: String,
    elapsedMs: Long,
    durationMs: Long,
    onScrub: (Float) -> Unit,
    onSeek: () -> Unit,
    enabled: Boolean = true,
    showRemaining: Boolean = false,
    onTimeToggle: (() -> Unit)? = null,
) {
    val active = enabled && durationMs > 0
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val progressColor = MaterialTheme.colorScheme.primary.copy(alpha = if (active) 1f else 0.38f)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = elapsedMs.toFloat().coerceIn(0f, durationMs.toFloat().coerceAtLeast(1f)),
            onValueChange = onScrub,
            onValueChangeFinished = onSeek,
            valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
            enabled = active,
            modifier =
                Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    contentDescription = "$label timeline"
                },
            thumb = {
                // Center the circle within the slider's minimum-height thumb slot.
                Box(Modifier.width(12.dp).height(48.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(12.dp).background(progressColor, CircleShape))
                }
            },
            track = { state ->
                Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                    val fraction = (state.value / state.valueRange.endInclusive).coerceIn(0f, 1f)
                    val start = if (rtl) size.width else 0f
                    val end = if (rtl) 0f else size.width
                    drawLine(
                        trackColor,
                        Offset(start, center.y),
                        Offset(end, center.y),
                        strokeWidth = size.height,
                        cap = StrokeCap.Round,
                    )
                    if (fraction > 0f)
                        drawLine(
                            progressColor,
                            Offset(start, center.y),
                            Offset(start + (end - start) * fraction, center.y),
                            strokeWidth = size.height,
                            cap = StrokeCap.Round,
                        )
                }
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (active) duration(elapsedMs) else "Unavailable",
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                if (!active) "Duration unknown"
                else if (showRemaining)
                    "${duration(remainingAudioTime(durationMs, elapsedMs))} left"
                else duration(durationMs) + if (onTimeToggle != null) " total" else "",
                modifier =
                    if (onTimeToggle != null && active)
                        Modifier.clickable(
                                role = Role.Button,
                                onClickLabel =
                                    if (showRemaining) "Show total duration"
                                    else "Show time remaining",
                                onClick = onTimeToggle,
                            )
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 4.dp)
                    else Modifier,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

fun remainingAudioTime(durationMs: Long, elapsedMs: Long): Long =
    durationMs.coerceAtLeast(0) - elapsedMs.coerceIn(0, durationMs.coerceAtLeast(0))

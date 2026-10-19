package app.bookorbit.community.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

@Composable
fun PlaybackTimeline(
    label: String,
    elapsedMs: Long,
    durationMs: Long,
    onScrub: (Float) -> Unit,
    onSeek: () -> Unit,
    markers: List<Float> = emptyList(),
    enabled: Boolean = true,
) {
    val markerColor = MaterialTheme.colorScheme.onSurfaceVariant
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.fillMaxWidth()) {
            Slider(
                value = elapsedMs.toFloat().coerceIn(0f, durationMs.toFloat().coerceAtLeast(1f)),
                onValueChange = onScrub,
                onValueChangeFinished = onSeek,
                valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                enabled = enabled && durationMs > 0,
                modifier =
                    Modifier.fillMaxWidth().semantics { contentDescription = "$label timeline" },
            )
            if (enabled && markers.isNotEmpty())
                Canvas(Modifier.matchParentSize().padding(horizontal = 10.dp)) {
                    var lastX = -Float.MAX_VALUE
                    markers.forEach { fraction ->
                        val x = size.width * fraction
                        if (x - lastX >= 4.dp.toPx()) {
                            val displayedX = if (rtl) size.width - x else x
                            drawLine(
                                markerColor,
                                Offset(displayedX, center.y - 4.dp.toPx()),
                                Offset(displayedX, center.y + 4.dp.toPx()),
                                strokeWidth = 2.dp.toPx(),
                            )
                            lastX = x
                        }
                    }
                }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (enabled) duration(elapsedMs) else "Unavailable",
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                if (enabled) duration(durationMs) else "Duration unknown",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

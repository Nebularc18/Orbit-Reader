package app.bookorbit.community.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput

@Composable
fun PdfTextSelectionOverlay(
    width: Double,
    height: Double,
    modifier: Modifier,
    onSelect: (Double, Double, Double, Double) -> Unit,
) {
    var start by remember { mutableStateOf<Offset?>(null) }
    var end by remember { mutableStateOf<Offset?>(null) }
    Canvas(
        modifier
            .pointerInput(width, height) {
                detectTapGestures { point ->
                    val fit =
                        PdfPageFit(width, height, size.width.toDouble(), size.height.toDouble())
                    fit.point(point.x.toDouble(), point.y.toDouble())?.let {
                        onSelect(it.first, it.second, it.first, it.second)
                    }
                }
            }
            .pointerInput(width, height) {
                detectDragGestures(
                    onDragStart = {
                        start = it
                        end = it
                    },
                    onDragCancel = {
                        start = null
                        end = null
                    },
                    onDragEnd = {
                        val fit =
                            PdfPageFit(width, height, size.width.toDouble(), size.height.toDouble())
                        val a = start?.let { fit.point(it.x.toDouble(), it.y.toDouble()) }
                        val b = end?.let { fit.point(it.x.toDouble(), it.y.toDouble()) }
                        if (a != null && b != null) onSelect(a.first, a.second, b.first, b.second)
                        start = null
                        end = null
                    },
                ) { change, _ ->
                    change.consume()
                    end = change.position
                }
            }
    ) {
        val a = start
        val b = end
        if (a != null && b != null)
            drawRect(
                Color.Cyan.copy(alpha = 0.2f),
                Offset(minOf(a.x, b.x), minOf(a.y, b.y)),
                androidx.compose.ui.geometry.Size(
                    kotlin.math.abs(a.x - b.x),
                    kotlin.math.abs(a.y - b.y),
                ),
            )
    }
}

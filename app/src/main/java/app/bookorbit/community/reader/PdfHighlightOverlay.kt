package app.bookorbit.community.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import app.bookorbit.community.data.AnnotationPdf
import app.bookorbit.community.data.AnnotationRect
import kotlin.math.abs
import kotlin.math.min

/** Maps the fitted page through letterboxing into unrotated PDF page coordinates. */
data class PdfPageFit(
    val pageWidth: Double,
    val pageHeight: Double,
    val viewWidth: Double,
    val viewHeight: Double,
) {
    val scale = min(viewWidth / pageWidth, viewHeight / pageHeight)
    val left = (viewWidth - pageWidth * scale) / 2
    val top = (viewHeight - pageHeight * scale) / 2

    fun point(x: Double, y: Double): Pair<Double, Double>? {
        if (scale <= 0 || !scale.isFinite()) return null
        val px = (x - left) / scale
        val py = (y - top) / scale
        return if (px in 0.0..pageWidth && py in 0.0..pageHeight) px to py else null
    }

    fun area(ax: Double, ay: Double, bx: Double, by: Double): AnnotationRect? {
        if (scale <= 0 || !scale.isFinite()) return null
        val x1 = ((ax - left) / scale).coerceIn(0.0, pageWidth)
        val x2 = ((bx - left) / scale).coerceIn(0.0, pageWidth)
        val y1 = ((ay - top) / scale).coerceIn(0.0, pageHeight)
        val y2 = ((by - top) / scale).coerceIn(0.0, pageHeight)
        if (abs(x1 - x2) < 1 || abs(y1 - y2) < 1) return null
        return AnnotationRect(min(x1, x2), min(y1, y2), abs(x1 - x2), abs(y1 - y2))
    }
}

@Composable
fun PdfHighlightOverlay(
    width: Double,
    height: Double,
    marking: Boolean,
    marks: List<AnnotationPdf>,
    modifier: Modifier,
    onArea: (AnnotationRect) -> Unit,
) {
    var start by remember { mutableStateOf<Offset?>(null) }
    var end by remember { mutableStateOf<Offset?>(null) }
    Canvas(
        modifier.then(
            if (marking)
                Modifier.pointerInput(width, height) {
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
                            val a = start
                            val b = end
                            if (a != null && b != null)
                                PdfPageFit(
                                        width,
                                        height,
                                        size.width.toDouble(),
                                        size.height.toDouble(),
                                    )
                                    .area(
                                        a.x.toDouble(),
                                        a.y.toDouble(),
                                        b.x.toDouble(),
                                        b.y.toDouble(),
                                    )
                                    ?.let(onArea)
                            start = null
                            end = null
                        },
                    ) { change, _ ->
                        change.consume()
                        end = change.position
                    }
                }
            else Modifier
        )
    ) {
        val fit = PdfPageFit(width, height, size.width.toDouble(), size.height.toDouble())
        fun draw(rect: AnnotationRect) {
            drawRect(
                Color.Yellow.copy(alpha = 0.35f),
                Offset(
                    (fit.left + rect.x * fit.scale).toFloat(),
                    (fit.top + rect.y * fit.scale).toFloat(),
                ),
                Size((rect.width * fit.scale).toFloat(), (rect.height * fit.scale).toFloat()),
            )
        }
        marks.flatMap { it.rects }.forEach(::draw)
        val a = start
        val b = end
        if (a != null && b != null)
            fit.area(a.x.toDouble(), a.y.toDouble(), b.x.toDouble(), b.y.toDouble())?.let(::draw)
    }
}

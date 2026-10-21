package app.bookorbit.community.reader

import app.bookorbit.community.data.AnnotationPdf
import app.bookorbit.community.data.AnnotationRect

/** Text and geometry from the same native selection, in PDF page points. */
data class PdfTextSelection(val text: String, val position: AnnotationPdf)

fun pdfTextSelection(
    page: Int,
    width: Double,
    height: Double,
    runs: List<Pair<String, List<AnnotationRect>>>,
): PdfTextSelection? {
    if (
        page < 0 ||
            !width.isFinite() ||
            !height.isFinite() ||
            width <= 0 ||
            height <= 0 ||
            runs.size > 1000 ||
            runs.sumOf { it.first.length.toLong() + 1 } > 10001
    )
        return null
    val text = runs.joinToString("\n") { it.first }.trim()
    if (text.isBlank() || text.length > 10000) return null
    val rects =
        runs
            .flatMap { it.second }
            .mapNotNull { rect ->
                if (
                    !listOf(rect.x, rect.y, rect.width, rect.height).all { it.isFinite() } ||
                        rect.width <= 0 ||
                        rect.height <= 0
                )
                    return@mapNotNull null
                val left = rect.x.coerceIn(0.0, width)
                val top = rect.y.coerceIn(0.0, height)
                val right = (rect.x + rect.width).coerceIn(0.0, width)
                val bottom = (rect.y + rect.height).coerceIn(0.0, height)
                if (right <= left || bottom <= top) null
                else AnnotationRect(left, top, right - left, bottom - top)
            }
    if (rects.isEmpty() || rects.size > 1000) return null
    val left = rects.minOf { it.x }
    val top = rects.minOf { it.y }
    val right = rects.maxOf { it.x + it.width }
    val bottom = rects.maxOf { it.y + it.height }
    return PdfTextSelection(
        text,
        AnnotationPdf(page, AnnotationRect(left, top, right - left, bottom - top), rects),
    )
}

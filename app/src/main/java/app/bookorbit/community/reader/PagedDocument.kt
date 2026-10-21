package app.bookorbit.community.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Point
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.models.selection.SelectionBoundary
import android.os.Build
import android.os.ParcelFileDescriptor
import app.bookorbit.community.data.AnnotationRect
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.max
import kotlinx.serialization.Serializable

@Serializable data class PageLocation(val page: Int = 0)

fun naturalPageCompare(left: String, right: String): Int {
    val tokens = Regex("[0-9]+|[^0-9]+")
    val a = tokens.findAll(left.lowercase(java.util.Locale.ROOT)).map { it.value }.toList()
    val b = tokens.findAll(right.lowercase(java.util.Locale.ROOT)).map { it.value }.toList()
    for (i in 0 until minOf(a.size, b.size)) {
        val x = a[i]
        val y = b[i]
        val result =
            if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0').ifEmpty { "0" }
                val ny = y.trimStart('0').ifEmpty { "0" }
                nx.length.compareTo(ny.length).takeIf { it != 0 } ?: nx.compareTo(ny)
            } else x.compareTo(y)
        if (result != 0) return result
    }
    return a.size.compareTo(b.size).takeIf { it != 0 } ?: left.compareTo(right)
}

class PagedDocument(file: File, pdf: Boolean) : Closeable {
    private val descriptor =
        if (pdf) ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) else null
    private val renderer =
        descriptor?.let {
            try {
                PdfRenderer(it)
            } catch (error: Exception) {
                it.close()
                throw error
            }
        }
    private val zip = if (pdf) null else ZipFile(file)
    private val images =
        zip?.entries()
            ?.asSequence()
            ?.filter {
                !it.isDirectory &&
                    it.name.substringAfterLast('.').lowercase() in
                        listOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif") &&
                    !it.name.startsWith("__MACOSX/")
            }
            ?.map { it.name }
            ?.toList()
            ?.sortedWith(::naturalPageCompare)
            .orEmpty()
    val pageCount = renderer?.pageCount ?: images.size

    init {
        if (pageCount !in 1..20000) {
            close()
            throw IllegalArgumentException(
                "This document has no supported pages or too many pages."
            )
        }
    }

    fun dimensions(index: Int): Pair<Double, Double> =
        renderer?.openPage(index)?.use { it.width.toDouble() to it.height.toDouble() }
            ?: (1.0 to 1.0)

    fun selectText(index: Int, ax: Double, ay: Double, bx: Double, by: Double): PdfTextSelection? {
        if (Build.VERSION.SDK_INT < 35) return null
        return renderer?.openPage(index)?.use { page ->
            fun boundary(x: Double, y: Double) =
                SelectionBoundary(
                    Point(x.toInt().coerceIn(0, page.width), y.toInt().coerceIn(0, page.height))
                )
            val selection = page.selectContent(boundary(ax, ay), boundary(bx, by)) ?: return null
            val contents = selection.selectedTextContents
            if (contents.size > 1000) return null
            pdfTextSelection(
                index,
                page.width.toDouble(),
                page.height.toDouble(),
                contents.map { content ->
                    content.text to
                        content.bounds.map { rect ->
                            AnnotationRect(
                                rect.left.toDouble(),
                                rect.top.toDouble(),
                                rect.width().toDouble(),
                                rect.height().toDouble(),
                            )
                        }
                },
            )
        }
    }

    fun render(index: Int): Bitmap {
        require(index in 0 until pageCount)
        renderer?.let { pdf ->
            return pdf.openPage(index).use { page ->
                val ratio = 2048.0 / max(page.width, page.height).coerceAtLeast(1)
                Bitmap.createBitmap(
                        (page.width * ratio).toInt().coerceAtLeast(1),
                        (page.height * ratio).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                    .apply {
                        eraseColor(Color.WHITE)
                        page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
            }
        }
        val archive = zip!!
        val entry = archive.getEntry(images[index])
        require(entry.size in 1..(32L * 1024 * 1024)) { "Comic image exceeds the supported size." }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        archive.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, options) }
        require(
            options.outWidth > 0 &&
                options.outHeight > 0 &&
                options.outWidth.toLong() * options.outHeight <= 200_000_000L
        ) {
            "Comic image is unsupported or too large."
        }
        var sample = 1
        while (max(options.outWidth, options.outHeight) / sample > 2048) sample *= 2
        return archive.getInputStream(entry).use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } ?: throw IllegalArgumentException("This comic image cannot be decoded.")
    }

    override fun close() {
        renderer?.close()
        descriptor?.close()
        zip?.close()
    }
}

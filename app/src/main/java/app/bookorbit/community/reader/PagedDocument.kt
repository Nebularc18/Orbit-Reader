package app.bookorbit.community.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Point
import android.graphics.pdf.PdfRenderer
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

class PagedDocument(private val file: File, pdf: Boolean, reflowable: Boolean = false) : Closeable {
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
    private val extended =
        if (reflowable)
            com.artifex.mupdf.fitz.Document.openDocument(file.absolutePath).apply {
                try {
                    require(!needsPassword()) { "Encrypted ebooks are not supported." }
                    if (isReflowable) layout(600f, 800f, 18f)
                } catch (e: Exception) {
                    destroy()
                    throw e
                }
            }
        else null
    private var textDocument: com.artifex.mupdf.fitz.Document? = null
    private val zip = if (pdf || reflowable) null else ZipFile(file)
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
    val pageCount = renderer?.pageCount ?: extended?.countPages() ?: images.size

    init {
        if (pageCount !in 1..20000) {
            close()
            throw IllegalArgumentException(
                "This document has no supported pages or too many pages."
            )
        }
    }

    fun dimensions(index: Int): Pair<Double, Double> {
        renderer?.openPage(index)?.use {
            return it.width.toDouble() to it.height.toDouble()
        }
        extended?.loadPage(index)?.let { page ->
            try {
                val bounds = page.bounds
                return (bounds.x1 - bounds.x0).toDouble() to (bounds.y1 - bounds.y0).toDouble()
            } finally {
                page.destroy()
            }
        }
        return 1.0 to 1.0
    }

    fun pageText(index: Int): String {
        val doc =
            extended
                ?: if (renderer != null) {
                    textDocument
                        ?: com.artifex.mupdf.fitz.Document.openDocument(file.absolutePath).also {
                            textDocument = it
                        }
                } else return ""
        val page = doc.loadPage(index)
        try {
            val bounds = page.bounds
            val text = page.toStructuredText("")
            try {
                return text
                    .copy(
                        com.artifex.mupdf.fitz.Point(bounds.x0, bounds.y0),
                        com.artifex.mupdf.fitz.Point(bounds.x1, bounds.y1),
                    )
                    .trim()
                    .take(32000)
            } finally {
                text.destroy()
            }
        } finally {
            page.destroy()
        }
    }

    fun selectText(index: Int, ax: Double, ay: Double, bx: Double, by: Double): PdfTextSelection? {
        if (renderer == null) return null
        val doc =
            textDocument
                ?: com.artifex.mupdf.fitz.Document.openDocument(file.absolutePath).also {
                    textDocument = it
                }
        val page = doc.loadPage(index)
        try {
            val bounds = page.bounds
            val (width, height) = dimensions(index)
            val sx = (bounds.x1 - bounds.x0) / width
            val sy = (bounds.y1 - bounds.y0) / height
            val a =
                com.artifex.mupdf.fitz.Point(
                    (ax * sx + bounds.x0).toFloat(),
                    (ay * sy + bounds.y0).toFloat(),
                )
            val b =
                com.artifex.mupdf.fitz.Point(
                    (bx * sx + bounds.x0).toFloat(),
                    (by * sy + bounds.y0).toFloat(),
                )
            val text = page.toStructuredText("")
            try {
                text.snapSelection(a, b, com.artifex.mupdf.fitz.StructuredText.SELECT_WORDS)
                val quote = text.copy(a, b)
                if (quote.length > 10000) return null
                val rects =
                    text.highlight(a, b).take(1001).map { quad ->
                        val r = quad.toRect()
                        AnnotationRect(
                            (r.x0 - bounds.x0) / sx,
                            (r.y0 - bounds.y0) / sy,
                            (r.x1 - r.x0) / sx,
                            (r.y1 - r.y0) / sy,
                        )
                    }
                if (
                    kotlin.math.abs(ax - bx) < 3 &&
                        kotlin.math.abs(ay - by) < 3 &&
                        rects.none {
                            ax >= it.x &&
                                ax <= it.x + it.width &&
                                ay >= it.y &&
                                ay <= it.y + it.height
                        }
                )
                    return null
                return pdfTextSelection(index, width, height, listOf(quote to rects))
            } finally {
                text.destroy()
            }
        } finally {
            page.destroy()
        }
    }

    fun render(index: Int): Bitmap {
        require(index in 0 until pageCount)
        extended?.loadPage(index)?.let { page ->
            try {
                val bounds = page.bounds
                val scale =
                    2048f / maxOf(bounds.x1 - bounds.x0, bounds.y1 - bounds.y0).coerceAtLeast(1f)
                return com.artifex.mupdf.fitz.android.AndroidDrawDevice.drawPage(
                    page,
                    com.artifex.mupdf.fitz.Matrix(scale),
                )
            } finally {
                page.destroy()
            }
        }
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
        textDocument?.destroy()
        extended?.destroy()
        renderer?.close()
        descriptor?.close()
        zip?.close()
    }
}

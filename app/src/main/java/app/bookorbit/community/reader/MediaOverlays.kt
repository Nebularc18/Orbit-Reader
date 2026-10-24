package app.bookorbit.community.reader

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.zip.ZipFile
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

data class OverlayClip(
    val textPath: String,
    val fragment: String,
    val quote: String,
    val audioPath: String,
    val startMs: Long,
    val endMs: Long,
)

fun smilMillis(value: String): Long {
    val raw = value.trim().removePrefix("npt=")
    val seconds =
        if (':' in raw) {
            val parts = raw.split(':')
            require(parts.size in 2..3)
            parts.fold(0.0) { total, part ->
                total * 60 + part.toDouble().also { require(it >= 0 && it.isFinite()) }
            }
        } else {
            val unit =
                listOf("min" to 60.0, "ms" to .001, "h" to 3600.0, "s" to 1.0).firstOrNull {
                    raw.endsWith(it.first)
                }
            (if (unit == null) raw else raw.removeSuffix(unit.first)).toDouble() *
                (unit?.second ?: 1.0)
        }
    require(seconds.isFinite() && seconds in 0.0..86400.0)
    return (seconds * 1000).toLong()
}

fun overlayPath(base: String, href: String): URI {
    val uri = URI(base).resolve(URI(href)).normalize()
    require(uri.scheme == null && uri.authority == null && uri.query == null)
    require(
        uri.path.isNotBlank() &&
            !uri.path.startsWith('/') &&
            uri.path.split('/').none { it == ".." } &&
            uri.path.none { it.code < 32 || it == '\\' }
    )
    require(uri.fragment.orEmpty().length <= 512 && uri.fragment.orEmpty().none { it.code < 32 })
    return uri
}

private fun ZipFile.overlayXml(path: String): ByteArray {
    val entry = getEntry(path) ?: error("Missing narration resource")
    require(entry.size in 0..(2 * 1024 * 1024))
    return getInputStream(entry).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(65536)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(output.size() + n <= 2 * 1024 * 1024)
            output.write(buffer, 0, n)
        }
        output.toByteArray()
    }
}

fun mediaOverlays(file: File, chapter: String): List<OverlayClip> =
    ZipFile(file).use { zip ->
        val container =
            Jsoup.parse(
                zip.overlayXml("META-INF/container.xml").inputStream(),
                null,
                "",
                Parser.xmlParser(),
            )
        val opfPath = overlayPath("", container.selectFirst("rootfile")!!.attr("full-path")).path
        val opf = Jsoup.parse(zip.overlayXml(opfPath).inputStream(), null, "", Parser.xmlParser())
        val items = opf.select("manifest > item")
        val chapterPath = overlayPath("", chapter).path
        val item =
            items.firstOrNull { overlayPath(opfPath, it.attr("href")).path == chapterPath }
                ?: return@use emptyList()
        val overlay =
            items.firstOrNull { it.id() == item.attr("media-overlay") } ?: return@use emptyList()
        val smilPath = overlayPath(opfPath, overlay.attr("href")).path
        val document =
            Jsoup.parse(
                EpubContent.sanitize(zip.overlayXml(chapterPath)).inputStream(),
                null,
                "",
                Parser.xmlParser(),
            )
        val smil = Jsoup.parse(zip.overlayXml(smilPath).inputStream(), null, "", Parser.xmlParser())
        val pars = smil.select("par")
        require(pars.size <= 5000)
        pars.map { par ->
            val text = overlayPath(smilPath, requireNotNull(par.selectFirst("text")).attr("src"))
            require(text.path == chapterPath && !text.fragment.isNullOrBlank())
            val audio = requireNotNull(par.selectFirst("audio"))
            val audioPath = overlayPath(smilPath, audio.attr("src")).path
            require(
                audioPath.substringAfterLast('.').lowercase() in
                    listOf("mp3", "m4a", "aac", "mp4", "ogg", "opus", "wav", "flac")
            )
            val start = smilMillis(audio.attr("clipBegin").ifBlank { "0" })
            val end = smilMillis(audio.attr("clipEnd"))
            require(end > start)
            OverlayClip(
                chapterPath,
                text.fragment,
                requireNotNull(document.getElementById(text.fragment)) {
                        "Missing timed text anchor"
                    }
                    .text()
                    .take(10000),
                audioPath,
                start,
                end,
            )
        }
    }

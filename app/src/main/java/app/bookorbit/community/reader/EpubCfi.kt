@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package app.bookorbit.community.reader

import java.io.File
import java.net.URI
import java.util.IdentityHashMap
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.readium.r2.shared.publication.Locator

/** Source paths survive removal of active content and HTML parser corrections. */
object EpubSourcePaths {
    const val PATH = "data-orbit-cfi"
    const val TEXT = "data-orbit-text"

    fun parse(bytes: ByteArray): Document {
        val source =
            Jsoup.parse(bytes.inputStream(), null, "", Parser.xmlParser()).apply {
                outputSettings().prettyPrint(false)
            }
        source.allElements.forEach { it.removeAttr(PATH).removeAttr(TEXT) }
        val root =
            source.children().firstOrNull { it.normalName() == "html" }
                ?: return Jsoup.parse(bytes.inputStream(), null, "").apply {
                    allElements.forEach { it.removeAttr(PATH).removeAttr(TEXT) }
                }
        fun mark(element: Element, path: String, depth: Int = 0) {
            if (depth > 256) return
            element.attr(PATH, path)
            var elements = 0
            var offset = 0
            val texts = JSONArray()
            element.childNodes().forEach { node ->
                when (node) {
                    is Element -> {
                        elements++
                        offset = 0
                        mark(node, "$path/${elements * 2}", depth + 1)
                    }
                    is TextNode -> {
                        texts.put(JSONArray(listOf(elements * 2 + 1, offset)))
                        offset += node.wholeText.replace("\r\n", "\n").replace('\r', '\n').length
                    }
                }
            }
            element.attr(TEXT, texts.toString())
        }
        mark(root, "")
        return Jsoup.parse(source.outerHtml())
    }

    fun textPaths(document: Document): IdentityHashMap<Node, String> {
        val result = IdentityHashMap<Node, String>()
        document.allElements.forEach { element ->
            val paths = runCatching { JSONArray(element.attr(TEXT)) }.getOrNull()
            element.textNodes().forEachIndexed { index, node ->
                paths?.optJSONArray(index)?.let {
                    result[node] = "${element.attr(PATH)}/${it.getInt(0)}:${it.getInt(1)}"
                }
            }
        }
        return result
    }

    fun retainTextPaths(document: Document, originals: IdentityHashMap<Node, String>) {
        document.allElements.forEach { element ->
            val paths = JSONArray()
            var adjacent = false
            element.childNodes().forEach { node ->
                if (node is TextNode) {
                    // Removing an intervening element can join unrelated source text runs.
                    // Refuse those ambiguous text offsets rather than sharing a false CFI.
                    if (adjacent) {
                        if (paths.length() > 0) paths.put(paths.length() - 1, JSONObject.NULL)
                    } else paths.put(originals[node] ?: JSONObject.NULL)
                    adjacent = true
                } else adjacent = false
            }
            if (element.hasAttr(PATH)) element.attr(TEXT, paths.toString())
        }
    }
}

data class CfiLocation(val packagePath: String, val start: String, val end: String? = null)

object EpubCfiSyntax {
    private fun withoutAssertions(value: String): String? {
        val out = StringBuilder()
        var assertion = false
        var escaped = false
        for (c in value) {
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '^') {
                escaped = true
                continue
            }
            if (c == '[') {
                if (assertion) return null
                assertion = true
            } else if (c == ']') {
                if (!assertion) return null
                assertion = false
            } else if (!assertion) out.append(c)
        }
        return if (assertion || escaped) null else out.toString()
    }

    fun parse(value: String): CfiLocation? {
        if (value.length > 2000 || !value.startsWith("epubcfi(") || !value.endsWith(')'))
            return null
        val body = withoutAssertions(value.substring(8, value.length - 1)) ?: return null
        val parts = body.split('!')
        if (parts.size != 2 || !Regex("(?:/[1-9][0-9]*){2}").matches(parts[0])) return null
        val range =
            parts[1].split(',').map { component ->
                if (';' !in component) component
                else {
                    if (!component.matches(Regex("[^;]+;s=[ab]"))) return null
                    component.substringBefore(';')
                }
            }
        val start: String
        val end: String?
        if (range.size == 1) {
            start = range[0]
            end = null
        } else if (range.size == 3) {
            start = range[0] + range[1]
            end = range[0] + range[2]
        } else return null
        val path = Regex("(?:/[1-9][0-9]*)+(?::[0-9]+)?")
        if (!path.matches(start) || (end != null && !path.matches(end))) return null
        return CfiLocation(parts[0], start, end)
    }

    fun format(location: CfiLocation): String {
        val end = location.end ?: return "epubcfi(${location.packagePath}!${location.start})"
        val a = location.start.substringBeforeLast('/').split('/').drop(1)
        val b = end.substringBeforeLast('/').split('/').drop(1)
        val common =
            a.zip(b).takeWhile { it.first == it.second }.joinToString("") { "/${it.first}" }
        require(common.isNotEmpty())
        return "epubcfi(${location.packagePath}!$common,${location.start.removePrefix(common)},${end.removePrefix(common)})"
    }
}

class EpubCfi(private val file: File) {
    private fun ZipFile.xml(path: String) =
        getInputStream(getEntry(path) ?: error("Missing EPUB resource")).use {
            val bytes = it.readBytes()
            require(bytes.size <= 32 * 1024 * 1024)
            bytes
        }

    private fun spine(zip: ZipFile): Map<String, String> {
        val container =
            Jsoup.parse(
                zip.xml("META-INF/container.xml").inputStream(),
                null,
                "",
                Parser.xmlParser(),
            )
        val opfPath =
            container.selectFirst("rootfile")?.attr("full-path") ?: error("Missing package")
        val opf = Jsoup.parse(zip.xml(opfPath).inputStream(), null, "", Parser.xmlParser())
        val packageElement = opf.selectFirst("package") ?: error("Missing package")
        val spine = packageElement.children().first { it.normalName() == "spine" }
        val step = (packageElement.children().indexOf(spine) + 1) * 2
        val items = opf.select("manifest > item").associate { it.id() to it.attr("href") }
        return spine
            .children()
            .mapIndexedNotNull { index, ref ->
                val href = items[ref.attr("idref")] ?: return@mapIndexedNotNull null
                val path = URI(opfPath).resolve(href).path
                path to "/$step/${(index + 1) * 2}"
            }
            .toMap()
    }

    private fun document(zip: ZipFile, href: String) =
        Jsoup.parse(EpubContent.sanitize(zip.xml(href)).inputStream(), null, "", Parser.xmlParser())

    fun fromLocator(locator: Locator): String? =
        runCatching {
                ZipFile(file).use { zip ->
                    val href = URI(locator.href.toString()).path
                    val packagePath = spine(zip)[href] ?: return null
                    val document = document(zip, href)
                    val locations = locator.toJSON().getJSONObject("locations")
                    val range = locations.optJSONObject("domRange")
                    fun endpoint(point: JSONObject): String? {
                        val element =
                            document.selectFirst(point.getString("cssSelector")) ?: return null
                        if (!element.hasAttr(EpubSourcePaths.PATH)) return null
                        val paths = JSONArray(element.attr(EpubSourcePaths.TEXT))
                        val textIndex = point.optInt("textNodeIndex", -1)
                        val base =
                            paths.optString(textIndex, "").takeIf { it.startsWith('/') }
                                ?: return null
                        val offset = point.optInt("charOffset", 0)
                        val text =
                            element.textNodes().getOrNull(textIndex)?.wholeText ?: return null
                        if (offset !in 0..text.length) return null
                        return base.substringBefore(':') +
                            ":" +
                            (base.substringAfter(':').toInt() + offset)
                    }
                    if (range != null) {
                        val start = endpoint(range.getJSONObject("start")) ?: return null
                        val end = range.optJSONObject("end")?.let { endpoint(it) ?: return null }
                        EpubCfiSyntax.format(CfiLocation(packagePath, start, end))
                    } else {
                        val selector = locations.optString("cssSelector")
                        val element = document.selectFirst(selector) ?: return null
                        val path =
                            element.attr(EpubSourcePaths.PATH).takeIf { it.startsWith('/') }
                                ?: return null
                        EpubCfiSyntax.format(CfiLocation(packagePath, path))
                    }
                }
            }
            .getOrNull()

    fun toLocator(cfi: String): Locator? =
        runCatching {
                val parsed = EpubCfiSyntax.parse(cfi) ?: return null
                ZipFile(file).use { zip ->
                    val href =
                        spine(zip).entries.firstOrNull { it.value == parsed.packagePath }?.key
                            ?: return null
                    val document = document(zip, href)
                    fun point(path: String): JSONObject? {
                        val elementPath =
                            if (path.contains(':')) path.substringBeforeLast('/') else path
                        val element =
                            document
                                .getElementsByAttributeValue(EpubSourcePaths.PATH, elementPath)
                                .firstOrNull() ?: return null
                        val selector = "[${EpubSourcePaths.PATH}=\"$elementPath\"]"
                        if (!path.contains(':'))
                            return JSONObject().put("cssSelector", selector).put("textNodeIndex", 0)
                        val offset = path.substringAfter(':').toInt()
                        val paths = JSONArray(element.attr(EpubSourcePaths.TEXT))
                        for (index in 0 until paths.length()) {
                            val base = paths.optString(index)
                            if (base.substringBefore(':') != path.substringBefore(':')) continue
                            val delta = offset - base.substringAfter(':').toInt()
                            val length =
                                element.textNodes().getOrNull(index)?.wholeText?.length ?: -1
                            if (delta !in 0..length) continue
                            return JSONObject()
                                .put("cssSelector", selector)
                                .put("textNodeIndex", index)
                                .put("charOffset", delta)
                        }
                        return null
                    }
                    val start = point(parsed.start) ?: return null
                    val end = parsed.end?.let { point(it) ?: return null }
                    val locations = JSONObject().put("cssSelector", start.getString("cssSelector"))
                    val json =
                        JSONObject()
                            .put("href", href)
                            .put("type", "application/xhtml+xml")
                            .put("locations", locations)
                    if (parsed.start.contains(':')) {
                        val range = JSONObject().put("start", start)
                        if (end != null) range.put("end", end)
                        locations.put("domRange", range)
                        val startElement =
                            document.selectFirst(start.getString("cssSelector")) ?: return null
                        val endElement =
                            end?.let { document.selectFirst(it.getString("cssSelector")) }
                                ?: startElement
                        val root =
                            (listOf(startElement) + startElement.parents()).firstOrNull {
                                it == endElement || endElement.parents().contains(it)
                            } ?: return null
                        val allText = mutableListOf<TextNode>()
                        root.forEachNode { node -> if (node is TextNode) allText.add(node) }
                        val startNode =
                            startElement.textNodes().getOrNull(start.getInt("textNodeIndex"))
                                ?: return null
                        val endNode =
                            end?.let {
                                endElement.textNodes().getOrNull(it.getInt("textNodeIndex"))
                            } ?: startNode
                        val text = allText.joinToString("") { it.wholeText }
                        val first = allText.indexOf(startNode)
                        val last = allText.indexOf(endNode)
                        if (first < 0 || last < first) return null
                        val from =
                            allText.take(first).sumOf { it.wholeText.length } +
                                start.optInt("charOffset")
                        val to =
                            if (end != null)
                                allText.take(last).sumOf { it.wholeText.length } +
                                    end.optInt("charOffset")
                            else (from + 32).coerceAtMost(text.length)
                        if (to < from || to > text.length || to - from > 10000) return null
                        if (to > from) {
                            locations.put(
                                "cssSelector",
                                "[${EpubSourcePaths.PATH}=\"${root.attr(EpubSourcePaths.PATH)}\"]",
                            )
                            json.put(
                                "text",
                                JSONObject()
                                    .put("highlight", text.substring(from, to))
                                    .put(
                                        "before",
                                        text.substring((from - 32).coerceAtLeast(0), from),
                                    )
                                    .put(
                                        "after",
                                        text.substring(to, (to + 32).coerceAtMost(text.length)),
                                    ),
                            )
                        }
                    }
                    Locator.fromJSON(json)
                }
            }
            .getOrNull()
}

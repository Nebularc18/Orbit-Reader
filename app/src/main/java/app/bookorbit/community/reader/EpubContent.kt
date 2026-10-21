package app.bookorbit.community.reader

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities
import org.jsoup.parser.Parser

/** Removes publication scripts before Readium injects its own reader controls. */
object EpubContent {
    fun sanitize(html: String): String = clean(Jsoup.parse(html)).outerHtml()

    fun sanitize(bytes: ByteArray): ByteArray =
        clean(EpubSourcePaths.parse(bytes)).outerHtml().toByteArray()

    fun sanitizeSvg(bytes: ByteArray): ByteArray {
        val document = Jsoup.parse(bytes.inputStream(), null, "", Parser.xmlParser())
        stripActiveContent(document)
        document.select("style").remove()
        document.allElements.forEach { element ->
            element.attributes().asList().forEach { attribute ->
                if (
                    attribute.key.lowercase() in listOf("href", "xlink:href", "src", "style") &&
                        (attribute.value.contains(':') ||
                            attribute.value.startsWith("//") ||
                            attribute.key == "style")
                )
                    element.removeAttr(attribute.key)
            }
        }
        return document.outerHtml().toByteArray()
    }

    private fun stripActiveContent(document: Document) {
        document
            .select(
                "script,iframe,object,embed,form,base,meta[http-equiv],link[rel=import],foreignObject"
            )
            .remove()
        document.allElements.forEach { element ->
            element.attributes().asList().forEach { attribute ->
                val name = attribute.key.lowercase()
                val value =
                    attribute.value.filterNot { it.isWhitespace() || it.code < 32 }.lowercase()
                if (
                    name.startsWith("on") ||
                        name == "srcdoc" ||
                        value.startsWith("javascript:") ||
                        value.startsWith("vbscript:")
                )
                    element.removeAttr(attribute.key)
            }
        }
    }

    private fun clean(document: Document): Document {
        document.charset(Charsets.UTF_8)
        document
            .outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(Entities.EscapeMode.xhtml)
            .prettyPrint(false)
        val sourceText = EpubSourcePaths.textPaths(document)
        stripActiveContent(document)
        EpubSourcePaths.retainTextPaths(document, sourceText)
        document
            .head()
            .prependElement("meta")
            .attr("http-equiv", "Content-Security-Policy")
            .attr(
                "content",
                "default-src 'none'; img-src https://readium data:; font-src https://readium data:; style-src https://readium 'unsafe-inline'; script-src https://readium/assets/ 'unsafe-inline'; connect-src 'none'; frame-src 'none'; media-src 'none'; form-action 'none'; base-uri 'none'",
            )
        return document
    }
}

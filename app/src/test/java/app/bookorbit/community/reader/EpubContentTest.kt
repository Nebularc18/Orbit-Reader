package app.bookorbit.community.reader

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28])
class EpubContentTest {
    @Test
    fun `reader retains text images anchors and formatting while removing active content`() {
        val html =
            """<html><head><base href="https://example.com/"><script>bad()</script><meta http-equiv="refresh" content="0;url=https://example.com"></head>
            <body onload="bad()"><h1 id="chapter">Chapter</h1><p>Words <em>in italics</em></p><img src="cover.jpg" onerror="bad()">
            <a href="java&#x73;cript:bad()">Bad link</a><iframe src="https://example.com/"></iframe><form><input></form><svg onload="bad()"><script>bad()</script></svg></body></html>"""
        val result = Jsoup.parse(EpubContent.sanitize(html))
        assertEquals("Chapter", result.getElementById("chapter")!!.text())
        assertEquals("in italics", result.selectFirst("em")!!.text())
        assertEquals("cover.jpg", result.selectFirst("img")!!.attr("src"))
        assertTrue(result.select("script,iframe,form,base,[onload],[onerror]").isEmpty())
        assertFalse(result.selectFirst("a")!!.hasAttr("href"))
        val csp = result.selectFirst("meta[http-equiv=Content-Security-Policy]")!!.attr("content")
        assertTrue(csp.contains("connect-src 'none'"))
        assertTrue(csp.contains("script-src https://readium/assets/"))
        assertFalse(result.select("meta[http-equiv=refresh]").isNotEmpty())
    }

    @Test
    fun `SVG keeps illustrations and removes scripts event handlers and remote resources`() {
        val svg =
            """<svg xmlns="http://www.w3.org/2000/svg" onload="bad()"><circle cx="1" r="5"/><script>bad()</script><image href="https://example.com/a.png"/><foreignObject><p>unsafe</p></foreignObject></svg>"""
        val safe = EpubContent.sanitizeSvg(svg.toByteArray()).toString(Charsets.UTF_8)
        assertTrue(safe.contains("<circle"))
        assertFalse(safe.contains("bad()"))
        assertFalse(safe.contains("https://example.com"))
        assertFalse(safe.contains("foreignObject"))
    }

    @Test
    fun `UTF16 content retains non ASCII text`() {
        val html = "<html><head><meta charset=\"UTF-16\"></head><body><p>Héllo 世界</p></body></html>"
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + html.toByteArray(Charsets.UTF_16BE)
        val safe = EpubContent.sanitize(bytes).toString(Charsets.UTF_8)
        assertTrue(safe.contains("Héllo 世界"))
    }

    @Test
    fun `sanitized XHTML remains well formed with XML void tags and UTF8 encoding`() {
        val safe =
            EpubContent.sanitize(
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Test</title></head><body><p>A &amp; B&nbsp;</p><img src=\"a.jpg\"/><br/></body></html>"
            )
        val document =
            DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(ByteArrayInputStream(safe.toByteArray()))
        assertEquals("html", document.documentElement.tagName)
        assertTrue(safe.contains("charset=\"UTF-8\""))
    }
}

package app.bookorbit.community.reader

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EpubCfiTest {
    private fun book(action: (EpubCfi) -> Unit) {
        val file = File.createTempFile("source-paths", ".epub")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                mapOf(
                        "META-INF/container.xml" to
                            "<container><rootfiles><rootfile full-path=\"OPS/book.opf\"/></rootfiles></container>",
                        "OPS/book.opf" to
                            "<package><metadata/><manifest><item id=\"one\" href=\"one.xhtml\"/><item id=\"two\" href=\"two.xhtml\"/></manifest><spine><itemref idref=\"one\"/><itemref idref=\"two\"/></spine></package>",
                        "OPS/one.xhtml" to
                            "<html><head/><body><script>unsafe()</script><p id=\"p\">Hello <em>world</em> again</p><table><tr><td>Cell</td></tr></table></body></html>",
                        "OPS/two.xhtml" to
                            "<html><head/><body><p>Second 😀 chapter</p></body></html>",
                    )
                    .forEach { (name, value) ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(value.toByteArray())
                        zip.closeEntry()
                    }
            }
            action(EpubCfi(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `range survives script removal and round trips source offsets`() = book { bridge ->
        val cfi = "epubcfi(/6/2!/4/4,/1:2,/3:4)"
        val locator = bridge.toLocator(cfi)!!
        assertEquals("llo world aga", locator.text.highlight)
        assertEquals("OPS/one.xhtml", locator.href.toString())
        assertEquals(EpubCfiSyntax.parse(cfi), EpubCfiSyntax.parse(bridge.fromLocator(locator)!!))
    }

    @Test
    fun `table correction and a second spine item keep original paths`() = book { bridge ->
        for (cfi in listOf("epubcfi(/6/2!/4/6/2/2/1:2)", "epubcfi(/6/4!/4/2/1:10)")) {
            val locator = bridge.toLocator(cfi)!!
            assertEquals(cfi, bridge.fromLocator(locator))
        }
    }

    @Test
    fun `readium selection resolves original source path`() = book { bridge ->
        val point =
            JSONObject().put("cssSelector", "#p").put("textNodeIndex", 0).put("charOffset", 3)
        val locator =
            Locator.fromJSON(
                JSONObject()
                    .put("href", "OPS/one.xhtml")
                    .put("type", "application/xhtml+xml")
                    .put(
                        "locations",
                        JSONObject().put("domRange", JSONObject().put("start", point)),
                    )
            )!!
        assertEquals("epubcfi(/6/2!/4/4/1:3)", bridge.fromLocator(locator))
        assertNull(bridge.toLocator("epubcfi(/6/2!/4/4/1:999)"))
        assertNull(bridge.toLocator("epubcfi(/6/12!/4/2)"))
    }

    @Test
    fun `assertions escaped delimiters and unsupported positions are bounded`() {
        assertEquals(
            CfiLocation("/6/2", "/4/2/1:3"),
            EpubCfiSyntax.parse("epubcfi(/6/2[id^]with^,punctuation]!/4/2/1:3[ab,cd])"),
        )
        for (bad in
            listOf(
                "javascript:alert(1)",
                "epubcfi(/6/2!/4/2~2)",
                "epubcfi(/6/2!/4/2[broken)",
                "epubcfi(/6/2!/4/2,foo)",
            )) assertNull(EpubCfiSyntax.parse(bad))
    }
}

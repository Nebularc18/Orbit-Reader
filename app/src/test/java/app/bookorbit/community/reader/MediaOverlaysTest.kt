package app.bookorbit.community.reader

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28])
class MediaOverlaysTest {
    @Test
    fun `SMIL clock values and archive paths reject invalid resources`() {
        assertEquals(1250L, smilMillis("npt=00:01.250"))
        assertEquals(90000L, smilMillis("1.5min"))
        assertEquals(120L, smilMillis("120ms"))
        assertEquals(
            "OPS/narration.mp3",
            overlayPath("OPS/timing/one.smil", "../narration.mp3").path,
        )
        for (bad in
            listOf(
                "https://example.com/a.mp3",
                "../../escape.mp3",
                "/absolute.mp3",
                "%2e%2e/%2e%2e/x",
            )) assertThrows(Exception::class.java) { overlayPath("OPS/one.smil", bad) }
        for (bad in listOf("NaN", "-1s", "90000s")) assertThrows(Exception::class.java) {
            smilMillis(bad)
        }
    }

    @Test
    fun `overlay clips use actual chapter anchors and audio boundaries`() {
        val file = File.createTempFile("overlay-test", ".epub")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                mapOf(
                        "META-INF/container.xml" to
                            "<container><rootfile full-path='OPS/package.opf'/></container>",
                        "OPS/package.opf" to
                            "<package><manifest><item id='one' href='one.xhtml' media-overlay='timing'/><item id='timing' href='one.smil'/><item id='two' href='two.xhtml'/></manifest></package>",
                        "OPS/one.xhtml" to
                            "<html><body><p id='p0'>Hello <em>moon</em></p><p id='p1'>Next passage</p><script>unsafe()</script></body></html>",
                        "OPS/one.smil" to
                            "<smil><body><seq><par><text src='one.xhtml#p0'/><audio src='audio.mp3' clipBegin='0s' clipEnd='1.25s'/></par><par><text src='one.xhtml#p1'/><audio src='other.mp3' clipBegin='2s' clipEnd='3s'/></par></seq></body></smil>",
                    )
                    .forEach { (name, value) ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(value.toByteArray())
                        zip.closeEntry()
                    }
            }
            val clips = mediaOverlays(file, "OPS/one.xhtml")
            assertEquals(2, clips.size)
            assertEquals("Hello moon", clips[0].quote)
            assertEquals(1250L, clips[0].endMs)
            assertEquals(2000L, clips[1].startMs)
            assertEquals("OPS/other.mp3", clips[1].audioPath)
            assertTrue(mediaOverlays(file, "OPS/two.xhtml").isEmpty())
        } finally {
            file.delete()
        }
    }
}

package app.bookorbit.community.reader

import java.nio.file.Files
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class ReadAloudTest {
    @Test
    fun `speech is sentence based bounded and whitespace normalized`() {
        assertEquals(
            listOf("First sentence.", "Second sentence!"),
            speechSentences(" First  sentence.\nSecond sentence! ", Locale.US),
        )
        assertTrue(speechSentences(" \n ").isEmpty())
        val chunks = speechSentences("x".repeat(400000), Locale.US)
        assertEquals(300000, chunks.sumOf { it.length })
        assertTrue(chunks.all { it.length <= 3000 })
    }

    @Test
    fun `chapter extraction removes active content and rejects external paths`() {
        val file = Files.createTempFile("speech", ".epub").toFile()
        try {
            ZipOutputStream(file.outputStream()).use {
                it.putNextEntry(ZipEntry("EPUB/chapter.xhtml"))
                it.write(
                    "<html><body><nav>Contents</nav><p>Read this.</p><script>secret()</script><style>hidden</style></body></html>"
                        .toByteArray()
                )
                it.closeEntry()
            }
            val text = epubSpeechText(file, "EPUB/chapter.xhtml#start")
            assertTrue(text.contains("Read this."))
            assertFalse(text.contains("secret"))
            assertFalse(text.contains("Contents"))
            assertThrows(IllegalArgumentException::class.java) {
                epubSpeechText(file, "https://example.org/chapter")
            }
            assertThrows(IllegalArgumentException::class.java) {
                epubSpeechText(file, "EPUB/../chapter.xhtml")
            }
            assertThrows(IllegalStateException::class.java) {
                epubSpeechText(file, "missing.xhtml")
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `compressed oversized chapters are bounded before parsing`() {
        val file = Files.createTempFile("speech-large", ".epub").toFile()
        try {
            ZipOutputStream(file.outputStream()).use {
                it.putNextEntry(ZipEntry("large.xhtml"))
                it.write(ByteArray(2 * 1024 * 1024 + 1) { 65 })
                it.closeEntry()
            }
            assertThrows(IllegalArgumentException::class.java) {
                epubSpeechText(file, "large.xhtml")
            }
        } finally {
            file.delete()
        }
    }
}

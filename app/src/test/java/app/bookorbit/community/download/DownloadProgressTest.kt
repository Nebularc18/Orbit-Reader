package app.bookorbit.community.download

import app.bookorbit.community.data.AudioAsset
import org.junit.Assert.*
import org.junit.Test

class DownloadProgressTest {
    private fun asset(size: Long?) = AudioAsset("id", 1, 0, "m4b", 1000, size, "etag")

    @Test
    fun `progress combines completed files with the current file`() {
        val progress = DownloadProgress(listOf(asset(100), asset(300)))
        progress.update(0, 100)
        progress.update(1, 100)
        assertEquals(200L, progress.downloadedBytes)
        assertEquals(400L, progress.totalBytes)
        assertEquals(0.5f, progress.fraction!!, 0.001f)
    }

    @Test
    fun `unknown sizes stay indeterminate until content lengths are known`() {
        val progress = DownloadProgress(listOf(asset(null), asset(200)))
        progress.update(0, 50)
        assertNull(progress.fraction)
        progress.update(0, 100, 200)
        assertEquals(0.25f, progress.fraction!!, 0.001f)
    }

    @Test
    fun `large downloads use long byte totals`() {
        val progress = DownloadProgress(listOf(asset(3_000_000_000), asset(3_000_000_000)))
        progress.update(0, 3_000_000_000)
        assertEquals(6_000_000_000L, progress.totalBytes)
        assertEquals(0.5f, progress.fraction!!, 0.001f)
    }
}

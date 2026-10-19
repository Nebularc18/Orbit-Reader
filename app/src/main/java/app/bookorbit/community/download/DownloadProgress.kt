package app.bookorbit.community.download

import app.bookorbit.community.data.AudioAsset

class DownloadProgress(assets: List<AudioAsset>) {
    private val sizes = assets.map { it.sizeBytes ?: -1L }.toLongArray()
    private val received = LongArray(assets.size)
    val downloadedBytes: Long
        get() = received.sum()

    val totalBytes: Long?
        get() = if (sizes.all { it >= 0 }) sizes.sum() else null

    val fraction: Float?
        get() =
            totalBytes
                ?.takeIf { it > 0 }
                ?.let { (downloadedBytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }

    fun update(index: Int, bytes: Long, contentLength: Long? = null) {
        if (sizes[index] < 0 && contentLength != null && contentLength >= 0)
            sizes[index] = contentLength
        received[index] = bytes.coerceAtLeast(0)
    }
}

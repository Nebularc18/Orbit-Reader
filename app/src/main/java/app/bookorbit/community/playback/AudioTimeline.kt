package app.bookorbit.community.playback

import app.bookorbit.community.data.AudioManifest

data class AudioSeek(val assetIndex: Int, val positionMs: Long)

class AudioTimeline(val manifest: AudioManifest) {
    val hasDurations = manifest.assets.all { (it.durationMs ?: 0) > 0 }
    private val offsets =
        LongArray(manifest.assets.size + 1).apply {
            manifest.assets.forEachIndexed { index, asset ->
                this[index + 1] = this[index] + (asset.durationMs ?: 0).coerceAtLeast(0)
            }
        }
    val durationMs = offsets.last()
    val chapterFractions =
        if (hasDurations && durationMs > 0)
            manifest.chapters
                .map { it.startMs.toFloat() / durationMs }
                .filter { it > 0 && it < 1 }
                .distinct()
        else emptyList()

    fun elapsed(assetIndex: Int, positionMs: Long): Long =
        offsets[assetIndex.coerceIn(0, manifest.assets.lastIndex)] + positionMs.coerceAtLeast(0)

    fun seek(elapsedMs: Long): AudioSeek? {
        if (!hasDurations || manifest.assets.isEmpty()) return null
        val time = elapsedMs.coerceIn(0, durationMs)
        var low = 0
        var high = manifest.assets.lastIndex
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (offsets[middle] <= time) low = middle else high = middle - 1
        }
        return AudioSeek(low, (time - offsets[low]).coerceAtMost(manifest.assets[low].durationMs!!))
    }

    fun chapterIndex(elapsedMs: Long): Int {
        var low = 0
        var high = manifest.chapters.lastIndex
        var result = -1
        while (low <= high) {
            val middle = (low + high) / 2
            if (manifest.chapters[middle].startMs <= elapsedMs) {
                result = middle
                low = middle + 1
            } else high = middle - 1
        }
        if (result < 0) return -1
        val chapter = manifest.chapters[result]
        return if (
            elapsedMs < chapter.endMs ||
                (result == manifest.chapters.lastIndex && elapsedMs == chapter.endMs)
        )
            result
        else -1
    }
}

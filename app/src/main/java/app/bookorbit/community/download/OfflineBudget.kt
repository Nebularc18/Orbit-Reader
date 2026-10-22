package app.bookorbit.community.download

import android.content.Context
import app.bookorbit.community.data.PlaybackToolsStorage
import java.io.File
import java.io.IOException

class OfflineLimitReached :
    IOException(
        "Download storage limit reached. Remove a download or increase the limit in Playback & downloads."
    )

class OfflineBudget(private val context: Context) {
    private val reservations = mutableMapOf<String, Pair<String, Long>>()

    fun used(owner: String): Long =
        listOf(File(context.filesDir, "audio/$owner"), File(context.filesDir, "ebooks/$owner"))
            .sumOf { root ->
                if (!root.exists()) 0L
                else
                    root
                        .walkTopDown()
                        .filter {
                            it.isFile &&
                                (it.extension in setOf("audio", "epub", "pdf", "cbz", "part"))
                        }
                        .sumOf { it.length() }
            }

    @Synchronized
    fun reserve(owner: String, key: String, remaining: Long) {
        val limit = PlaybackToolsStorage(context, owner).options().storageGiB * 1024L * 1024 * 1024
        if (
            limit > 0 &&
                used(owner) +
                    reservations
                        .filterKeys { it != key }
                        .values
                        .filter { it.first == owner }
                        .sumOf { it.second } +
                    remaining.coerceAtLeast(0) > limit
        )
            throw OfflineLimitReached()
        reservations[key] = owner to remaining.coerceAtLeast(0)
    }

    @Synchronized
    fun check(owner: String) {
        val limit = PlaybackToolsStorage(context, owner).options().storageGiB * 1024L * 1024 * 1024
        if (limit > 0 && used(owner) > limit) throw OfflineLimitReached()
    }

    @Synchronized
    fun release(key: String) {
        reservations.remove(key)
    }
}

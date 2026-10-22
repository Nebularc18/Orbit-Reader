package app.bookorbit.community.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class PlaybackOptions(
    val smartResume: Boolean = false,
    val rewindSeconds: Int = 10,
    val autoplayQueue: Boolean = false,
    val continueSeries: Boolean = false,
    val prefetchNext: Boolean = false,
    val wifiOnly: Boolean = true,
    val storageGiB: Int = 0,
    val removeFinishedAudio: Boolean = false,
)

@Serializable
data class QueuedBook(val id: Int, val title: String, val authors: List<String> = emptyList())

class PlaybackToolsStorage(context: Context, private val owner: String) {
    private val prefs = context.getSharedPreferences("playback-tools", 0)

    companion object {
        private val lock = Any()
        const val MAX_QUEUE = 50
    }

    fun options(): PlaybackOptions =
        synchronized(lock) {
            runCatching {
                    wireJson.decodeFromString<PlaybackOptions>(
                        prefs.getString("$owner/options", null) ?: "{}"
                    )
                }
                .getOrDefault(PlaybackOptions())
        }

    fun options(value: PlaybackOptions) =
        synchronized(lock) {
            require(
                value.rewindSeconds in listOf(5, 10, 15, 30) &&
                    value.storageGiB in listOf(0, 1, 2, 5, 10)
            )
            check(prefs.edit().putString("$owner/options", wireJson.encodeToString(value)).commit())
        }

    fun queue(): List<QueuedBook> =
        synchronized(lock) {
            runCatching {
                    wireJson.decodeFromString<List<QueuedBook>>(
                        prefs.getString("$owner/queue", null) ?: "[]"
                    )
                }
                .getOrDefault(emptyList())
                .take(MAX_QUEUE)
        }

    fun add(book: QueuedBook, next: Boolean = false) =
        synchronized(lock) {
            require(book.id > 0)
            val list = queue().filterNot { it.id == book.id }
            require(list.size < MAX_QUEUE) {
                "The queue is full. Remove a book before adding another."
            }
            save(if (next) listOf(book) + list else list + book)
        }

    fun remove(id: Int) = synchronized(lock) { save(queue().filterNot { it.id == id }) }

    fun move(id: Int, delta: Int) =
        synchronized(lock) {
            val list = queue().toMutableList()
            val index = list.indexOfFirst { it.id == id }
            if (index >= 0) {
                val target = (index + delta).coerceIn(0, list.lastIndex)
                val book = list.removeAt(index)
                list.add(target, book)
                save(list)
            }
        }

    private fun save(list: List<QueuedBook>) {
        check(prefs.edit().putString("$owner/queue", wireJson.encodeToString(list)).commit())
    }
}

fun smartRewindMillis(enabled: Boolean, pausedMillis: Long, seconds: Int): Long =
    if (enabled && pausedMillis >= 60_000 && seconds in listOf(5, 10, 15, 30)) seconds * 1000L
    else 0

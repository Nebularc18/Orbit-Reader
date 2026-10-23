package app.bookorbit.community.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

val playbackSpeeds = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
val skipSeconds = listOf(5, 10, 15, 20, 30, 45, 60)

@Serializable
data class SkipIntervals(val backSeconds: Int = 15, val forwardSeconds: Int = 30) {
    fun validated(): SkipIntervals {
        require(backSeconds in skipSeconds && forwardSeconds in skipSeconds)
        return this
    }
}

@Serializable
data class AudioBookmark(
    val id: String = UUID.randomUUID().toString(),
    val assetId: String,
    val positionMs: Long,
    val manifestRevision: String,
    val title: String,
    val createdAt: String = Instant.now().toString(),
) {
    fun target(manifest: AudioManifest): Int? =
        manifest.assets
            .indexOfFirst { it.assetId == assetId }
            .takeIf { it >= 0 && manifest.revision == manifestRevision }
}

@Serializable
private data class BookListening(
    val speed: Float = 1f,
    val bookmarks: List<AudioBookmark> = emptyList(),
)

class ListeningStorage(context: Context, scope: String) {
    companion object {
        private val lock = Any()
        const val MAX_BOOKMARKS = 500
        const val MAX_TITLE_LENGTH = 120
    }

    private val root = File(context.filesDir, "listening/$scope").apply { mkdirs() }

    fun savedBookIds(): List<Int> =
        root
            .listFiles()
            .orEmpty()
            .mapNotNull {
                it.name
                    .removeSuffix(".bak")
                    .takeIf { name -> name.endsWith(".json") }
                    ?.removeSuffix(".json")
                    ?.toIntOrNull()
            }
            .distinct()
            .sorted()

    private fun book(bookId: Int): BookListening =
        read(File(root, "$bookId.json"))?.let { wireJson.decodeFromString(it) } ?: BookListening()

    fun speed(bookId: Int): Float = synchronized(lock) { book(bookId).speed }

    fun saveSpeed(bookId: Int, speed: Float) =
        synchronized(lock) {
            require(speed.isFinite() && speed in 0.25f..4f)
            write(
                File(root, "$bookId.json"),
                wireJson.encodeToString(book(bookId).copy(speed = speed)),
            )
        }

    fun intervals(): SkipIntervals =
        synchronized(lock) {
            read(File(root, "skip.json"))?.let {
                wireJson.decodeFromString<SkipIntervals>(it).validated()
            } ?: SkipIntervals()
        }

    fun saveIntervals(value: SkipIntervals) =
        synchronized(lock) {
            write(File(root, "skip.json"), wireJson.encodeToString(value.validated()))
        }

    fun showRemainingTime(): Boolean =
        synchronized(lock) { read(File(root, "remaining-time")) != "false" }

    fun saveShowRemainingTime(value: Boolean) =
        synchronized(lock) { write(File(root, "remaining-time"), value.toString()) }

    fun bookmarks(bookId: Int): List<AudioBookmark> = synchronized(lock) { book(bookId).bookmarks }

    fun addBookmark(bookId: Int, value: AudioBookmark) =
        synchronized(lock) {
            require(value.positionMs >= 0 && value.assetId.isNotBlank())
            val saved = book(bookId)
            require(saved.bookmarks.size < MAX_BOOKMARKS) {
                "This book has reached the 500 bookmark limit."
            }
            saveBookmarks(bookId, saved, saved.bookmarks + value.copy(title = title(value.title)))
        }

    fun renameBookmark(bookId: Int, id: String, title: String) =
        synchronized(lock) {
            val saved = book(bookId)
            saveBookmarks(
                bookId,
                saved,
                saved.bookmarks.map { if (it.id == id) it.copy(title = title(title)) else it },
            )
        }

    fun deleteBookmark(bookId: Int, id: String) =
        synchronized(lock) {
            val saved = book(bookId)
            saveBookmarks(bookId, saved, saved.bookmarks.filterNot { it.id == id })
        }

    private fun title(value: String): String {
        val result = value.trim()
        require(result.isNotEmpty() && result.length <= MAX_TITLE_LENGTH) {
            "Use a name with 1 to 120 characters."
        }
        return result
    }

    private fun saveBookmarks(bookId: Int, saved: BookListening, values: List<AudioBookmark>) =
        write(File(root, "$bookId.json"), wireJson.encodeToString(saved.copy(bookmarks = values)))

    private fun read(file: File): String? =
        if (file.exists() || File(file.path + ".bak").exists())
            AtomicFile(file).openRead().bufferedReader().use { it.readText() }
        else null

    private fun write(file: File, text: String) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(text.toByteArray())
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }
}

package app.bookorbit.community.reader

import android.content.Context
import android.util.AtomicFile
import app.bookorbit.community.data.AnnotationPdf
import app.bookorbit.community.data.wireJson
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class ReadingNote(
    val id: String = UUID.randomUUID().toString(),
    val revision: String,
    val locator: String,
    val title: String,
    val quote: String = "",
    val note: String = "",
    val highlight: Boolean = false,
    val cfi: String? = null,
    val pdf: AnnotationPdf? = null,
    val serverId: Int? = null,
)

class ReadingNotes(context: Context, owner: String, fileId: Int) {
    companion object {
        private val lock = Any()
    }

    private val file =
        File(context.filesDir, "ebooks/$owner/$fileId/notes.json").apply { parentFile!!.mkdirs() }

    fun all(): List<ReadingNote> =
        synchronized(lock) {
            if (!file.exists() && !File(file.path + ".bak").exists()) emptyList()
            else
                AtomicFile(file).openRead().bufferedReader().use {
                    wireJson.decodeFromString(it.readText())
                }
        }

    fun save(value: ReadingNote) =
        synchronized(lock) {
            require(
                value.title.isNotBlank() &&
                    value.title.length <= 120 &&
                    value.note.length <= 10000 &&
                    value.quote.length <= 10000 &&
                    value.locator.length <= 64000
            )
            val current = all()
            require(current.size < 500 || current.any { it.id == value.id }) {
                "This book has reached its 500 saved-note limit."
            }
            write(current.filterNot { it.id == value.id } + value)
        }

    fun delete(id: String) = synchronized(lock) { write(all().filterNot { it.id == id }) }

    private fun write(notes: List<ReadingNote>) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(wireJson.encodeToString(notes).toByteArray())
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }
}

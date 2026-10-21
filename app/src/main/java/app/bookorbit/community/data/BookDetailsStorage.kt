package app.bookorbit.community.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.encodeToString

class BookDetailsStorage(context: Context, scope: String) {
    private val root = File(context.filesDir, "book-details/$scope").apply { mkdirs() }

    fun read(bookId: Int): EbookDetail? =
        runCatching {
                AtomicFile(File(root, "$bookId.json")).openRead().bufferedReader().use {
                    wireJson.decodeFromString<EbookDetail>(it.readText())
                }
            }
            .getOrNull()

    fun save(detail: EbookDetail) {
        val file = AtomicFile(File(root, "${detail.id}.json"))
        val stream = file.startWrite()
        try {
            stream.write(wireJson.encodeToString(detail).toByteArray())
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}

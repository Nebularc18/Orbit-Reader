package app.bookorbit.community.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable data class PendingListeningSession(val fileId: Int, val value: ListeningSessionWrite)

class ListeningSessionStorage(context: Context, scope: String) {
    companion object {
        private val lock = Any()
    }

    private val root = File(context.filesDir, "listening-sessions/$scope").apply { mkdirs() }

    fun save(value: PendingListeningSession) =
        synchronized(lock) {
            val atomic = AtomicFile(file(value.value.sessionId))
            val stream = atomic.startWrite()
            try {
                stream.write(wireJson.encodeToString(value).toByteArray())
                atomic.finishWrite(stream)
            } catch (error: Exception) {
                atomic.failWrite(stream)
                throw error
            }
        }

    fun pending(limit: Int = 100): List<PendingListeningSession> =
        synchronized(lock) {
            root
                .listFiles()
                .orEmpty()
                .asSequence()
                .map { it.name.removeSuffix(".bak") }
                .filter { it.matches(Regex("[a-zA-Z0-9-]{1,64}")) }
                .distinct()
                .take(limit)
                .map { read(File(root, it)) }
                .toList()
        }

    fun acknowledge(sent: PendingListeningSession) =
        synchronized(lock) {
            val file = file(sent.value.sessionId)
            if ((file.exists() || File(file.path + ".bak").exists()) && read(file) == sent) {
                AtomicFile(file).delete()
            }
        }

    private fun file(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,64}")))
        return File(root, id)
    }

    private fun read(file: File): PendingListeningSession =
        AtomicFile(file).openRead().bufferedReader().use {
            wireJson.decodeFromString(it.readText())
        }
}

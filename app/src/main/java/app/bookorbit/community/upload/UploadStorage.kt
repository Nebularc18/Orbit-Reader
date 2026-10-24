package app.bookorbit.community.upload

import android.content.Context
import android.util.AtomicFile
import app.bookorbit.community.data.*
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class UploadJob(
    val id: String = UUID.randomUUID().toString(),
    val uri: String,
    val filename: String,
    val size: Long,
    val target: UploadTarget,
    val remoteId: String? = null,
    val phase: String = "queued",
    val bytes: Long = 0,
    val error: String? = null,
)

class UploadStorage(context: Context, owner: String) {
    private val root = File(context.filesDir, "uploads/$owner").apply { mkdirs() }

    private fun dir(id: String): File {
        UUID.fromString(id)
        return File(root, id).apply { mkdirs() }
    }

    fun content(id: String) = File(dir(id), "content")

    fun read(id: String): UploadJob =
        synchronized(lock) {
            AtomicFile(File(dir(id), "job.json")).openRead().bufferedReader().use {
                wireJson.decodeFromString<UploadJob>(it.readText())
            }
        }

    fun save(value: UploadJob) =
        synchronized(lock) {
            val file = AtomicFile(File(dir(value.id), "job.json"))
            val out = file.startWrite()
            try {
                out.write(wireJson.encodeToString(value).toByteArray())
                file.finishWrite(out)
            } catch (e: Exception) {
                file.failWrite(out)
                throw e
            }
        }

    fun update(value: UploadJob): Boolean =
        synchronized(lock) {
            if (!File(dir(value.id), "job.json").isFile) return@synchronized false
            save(value)
            true
        }

    fun all(): List<UploadJob> =
        synchronized(lock) {
            root
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory }
                .sortedByDescending { it.lastModified() }
                .mapNotNull { runCatching { read(it.name) }.getOrNull() }
                .take(30)
        }

    fun remove(id: String) = synchronized(lock) { check(dir(id).deleteRecursively()) }

    fun availableBytes(): Long = root.usableSpace

    companion object {
        private val lock = Any()
    }
}

fun validateUploadFile(name: String, size: Long, target: UploadTarget, caps: UploadCapabilities) {
    val library =
        caps.libraries.singleOrNull { it.id == target.libraryId }
            ?: error("Choose an available library.")
    require(caps.canUploadToLibrary && size in 1..caps.maxFileSizeBytes)
    require(
        name.isNotBlank() &&
            name.length <= 500 &&
            name.none { it == '/' || it == '\\' || it.code < 32 }
    )
    val format = name.substringAfterLast('.', "").lowercase()
    require(
        format in caps.supportedFormats.map(String::lowercase) &&
            (library.allowedFormats.isEmpty() ||
                format in library.allowedFormats.map(String::lowercase))
    ) {
        "This file format is not allowed in the selected library."
    }
    require(target.folderId == null || library.folders.any { it.id == target.folderId })
}

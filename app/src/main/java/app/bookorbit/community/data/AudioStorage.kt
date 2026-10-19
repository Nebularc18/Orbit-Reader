package app.bookorbit.community.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.encodeToString

class AudioStorage(context: Context, val scope: String) {
    companion object {
        private val positionLock = Any()
    }

    private val root = File(context.filesDir, "audio/$scope").apply { mkdirs() }

    fun bookDir(bookId: Int) = File(root, bookId.toString()).apply { mkdirs() }

    fun asset(bookId: Int, asset: AudioAsset): File {
        val version =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(asset.etag.toByteArray())
                .joinToString("") { "%02x".format(it) }
        return File(bookDir(bookId), "${asset.assetId}-$version.audio")
    }

    fun saveManifest(manifest: AudioManifest) =
        write(File(bookDir(manifest.book.id), "manifest.json"), wireJson.encodeToString(manifest))

    fun manifest(bookId: Int): AudioManifest? =
        read(File(bookDir(bookId), "manifest.json"))?.let {
            wireJson.decodeFromString<AudioManifest>(it).validated()
        }

    fun downloaded(manifest: AudioManifest): Boolean =
        manifest.assets.all {
            val file = asset(manifest.book.id, it)
            file.isFile &&
                (it.sizeBytes?.let { size -> file.length() == size } ?: (file.length() > 0))
        }

    fun position(bookId: Int): LocalPosition? =
        synchronized(positionLock) {
            read(File(bookDir(bookId), "position.json"))?.let { wireJson.decodeFromString(it) }
        }

    fun savePosition(bookId: Int, value: LocalPosition) =
        synchronized(positionLock) {
            write(File(bookDir(bookId), "position.json"), wireJson.encodeToString(value))
        }

    fun capturePosition(
        bookId: Int,
        assetId: String,
        milliseconds: Long,
        capturedAt: String,
        manifestRevision: String,
    ) =
        synchronized(positionLock) {
            savePosition(
                bookId,
                LocalPosition(
                    assetId,
                    milliseconds,
                    capturedAt,
                    position(bookId)?.revision ?: 0,
                    manifestRevision,
                ),
            )
        }

    fun acknowledge(bookId: Int, sent: LocalPosition, remote: PlaybackState) =
        synchronized(positionLock) {
            val latest = position(bookId) ?: return@synchronized
            savePosition(
                bookId,
                latest.copy(
                    revision = remote.revision,
                    dirty = latest.operationId != sent.operationId,
                ),
            )
        }

    fun downloadedBooks(page: Int): List<AudioManifest> =
        root
            .listFiles()
            .orEmpty()
            .asSequence()
            .mapNotNull { dir ->
                dir.name
                    .toIntOrNull()
                    ?.let { id -> runCatching { manifest(id) }.getOrNull() }
                    ?.takeIf(::downloaded)
            }
            .drop((page - 1) * 30)
            .take(30)
            .toList()

    fun deleteBook(bookId: Int) =
        bookDir(bookId)
            .listFiles()
            .orEmpty()
            .filter { it.name != "position.json" }
            .forEach { it.delete() }

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

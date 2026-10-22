package app.bookorbit.community.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.encodeToString

data class StoredDownload(val manifest: AudioManifest, val bytes: Long, val complete: Boolean)

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

    fun recentlyPlayed(): List<AudioManifest> =
        root
            .listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory && File(it, "position.json").isFile }
            .sortedByDescending { File(it, "position.json").lastModified() }
            .mapNotNull { dir ->
                dir.name.toIntOrNull()?.let { id ->
                    runCatching {
                            manifest(id)?.takeIf { position(id)?.manifestRevision == it.revision }
                        }
                        .getOrNull()
                }
            }
            .take(10)
            .toList()

    fun downloadedBooks(page: Int): List<AudioManifest> =
        storedBooks().filter(::downloaded).drop((page - 1) * 30).take(30).toList()

    private fun storedBooks(): Sequence<AudioManifest> =
        root
            .listFiles()
            .orEmpty()
            .sortedBy { it.name.toIntOrNull() ?: Int.MAX_VALUE }
            .asSequence()
            .mapNotNull { dir ->
                dir.name.toIntOrNull()?.let { id -> runCatching { manifest(id) }.getOrNull() }
            }

    fun markDownload(manifest: AudioManifest) {
        saveManifest(manifest)
        File(bookDir(manifest.book.id), "download").writeText("")
    }

    fun downloads(page: Int, sort: CatalogSort? = null): List<StoredDownload> {
        val candidates =
            storedBooks()
                .filter {
                    File(bookDir(it.book.id), "download").exists() || audioBytes(it.book.id) > 0
                }
                .map { BookCard(it.book.id, it.book.title, it.book.authors) }
        val ordered =
            if (sort == null) candidates else sortLocalBooks(candidates, sort).asSequence()
        return ordered
            .drop((page - 1) * 30)
            .take(30)
            .mapNotNull { card ->
                manifest(card.id)?.let { StoredDownload(it, audioBytes(card.id), downloaded(it)) }
            }
            .toList()
    }

    fun audioBytes(bookId: Int): Long =
        bookDir(bookId)
            .listFiles()
            .orEmpty()
            .filter { it.name.endsWith(".audio") || it.name.endsWith(".audio.part") }
            .sumOf { it.length() }

    fun usedBytes(): Long =
        root.listFiles().orEmpty().sumOf { dir ->
            dir.listFiles()
                .orEmpty()
                .filter { it.name.endsWith(".audio") || it.name.endsWith(".audio.part") }
                .sumOf { it.length() }
        }

    fun availableBytes(): Long = root.usableSpace

    fun removeAudioFiles(bookId: Int) {
        bookDir(bookId)
            .listFiles()
            .orEmpty()
            .filter {
                it.name == "download" ||
                    it.name.endsWith(".audio") ||
                    it.name.endsWith(".audio.part")
            }
            .forEach { check(it.delete() || !it.exists()) }
    }

    fun deleteBook(bookId: Int) =
        bookDir(bookId)
            .listFiles()
            .orEmpty()
            .filter { it.name != "position.json" }
            .forEach {
                if (!it.delete() && it.exists())
                    throw java.io.IOException("Could not remove downloaded audio")
            }

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

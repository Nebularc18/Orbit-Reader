package app.bookorbit.community.reader

import android.content.Context
import android.util.AtomicFile
import app.bookorbit.community.data.BookCard
import app.bookorbit.community.data.CatalogSort
import app.bookorbit.community.data.EbookFile
import app.bookorbit.community.data.sortLocalBooks
import app.bookorbit.community.data.wireJson
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class SavedEbook(
    val bookId: Int,
    val title: String,
    val authors: List<String>,
    val file: EbookFile,
)

@Serializable data class ReadingPlace(val revision: String, val locator: String)

@Serializable
data class ReadingPreferences(
    val fontSize: Double = 1.0,
    val theme: String = "light",
    val scroll: Boolean = false,
) {
    fun validated(): ReadingPreferences {
        require(
            fontSize.isFinite() && fontSize in 0.7..2.0 && theme in listOf("light", "sepia", "dark")
        )
        return this
    }
}

class EbookStorage(context: Context, scope: String) {
    companion object {
        const val MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024
        private val lock = Any()

        fun validateArchive(file: File, epub: Boolean = true) {
            require(file.length() <= MAX_DOWNLOAD_BYTES) { "This ebook exceeds the 512 MB limit." }
            ZipFile(file).use { zip ->
                var total = 0L
                var count = 0
                val paths = HashSet<String>()
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    require(++count <= 20_000 && paths.add(name)) {
                        "EPUB has too many or duplicate entries."
                    }
                    require(
                        !name.startsWith('/') &&
                            '\\' !in name &&
                            name.split('/').none { it == ".." } &&
                            ':' !in name
                    ) {
                        "EPUB contains an unsafe resource path."
                    }
                    require(entry.size in 0..(32L * 1024 * 1024)) {
                        "An EPUB resource is too large."
                    }
                    if (!entry.isDirectory)
                        zip.getInputStream(entry).use { source ->
                            val buffer = ByteArray(64 * 1024)
                            var actual = 0L
                            while (true) {
                                val length = source.read(buffer)
                                if (length < 0) break
                                actual += length
                                require(actual <= entry.size) {
                                    "An EPUB resource exceeds its declared size."
                                }
                            }
                            require(actual == entry.size) { "An EPUB resource is incomplete." }
                        }
                    total += entry.size
                    require(total <= 1024L * 1024 * 1024) {
                        "EPUB expands beyond the supported size."
                    }
                }
                require(!epub || zip.getEntry("META-INF/container.xml") != null) {
                    "This file is not an EPUB."
                }
            }
        }
    }

    private val root = File(context.filesDir, "ebooks/$scope").apply { mkdirs() }

    private fun dir(fileId: Int) = File(root, fileId.toString()).apply { mkdirs() }

    private fun version(file: EbookFile) =
        MessageDigest.getInstance("SHA-256").digest(file.revision.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    fun content(file: EbookFile) =
        File(
            dir(file.id),
            "${version(file)}.${if (file.format?.lowercase() == "pdf") "pdf" else if (file.format?.lowercase() in listOf("cbz", "cbr", "cb7")) "cbz" else "epub"}",
        )

    fun ready(file: EbookFile): Boolean = content(file).isFile && content(file).length() > 0

    fun saveBook(value: SavedEbook) =
        synchronized(lock) {
            write(File(dir(value.file.id), "book.json"), wireJson.encodeToString(value))
        }

    fun book(fileId: Int): SavedEbook? =
        synchronized(lock) {
            read(File(dir(fileId), "book.json"))?.let { wireJson.decodeFromString(it) }
        }

    fun books(page: Int): List<SavedEbook> =
        synchronized(lock) {
            root
                .listFiles()
                .orEmpty()
                .asSequence()
                .filter { it.isDirectory }
                .sortedBy { it.name.toIntOrNull() ?: Int.MAX_VALUE }
                .mapNotNull { it.name.toIntOrNull()?.let { id -> book(id) } }
                .filter { ready(it.file) }
                .drop((page - 1).coerceAtLeast(0) * 30)
                .take(30)
                .toList()
        }

    fun filesForBook(bookId: Int): List<SavedEbook> =
        synchronized(lock) {
            root
                .listFiles()
                .orEmpty()
                .asSequence()
                .filter { it.isDirectory }
                .mapNotNull { it.name.toIntOrNull()?.let { id -> book(id) } }
                .filter { it.bookId == bookId && ready(it.file) }
                .toList()
        }

    fun shelfBooks(page: Int, sort: CatalogSort? = null): List<SavedEbook> =
        synchronized(lock) {
            val candidates =
                root
                    .listFiles()
                    .orEmpty()
                    .asSequence()
                    .filter { it.isDirectory }
                    .sortedBy { it.name.toIntOrNull() ?: Int.MAX_VALUE }
                    .mapNotNull { it.name.toIntOrNull()?.let { id -> book(id) } }
                    .filter { ready(it.file) }
                    .distinctBy { it.bookId }
            if (sort == null) candidates.drop((page - 1) * 30).take(30).toList()
            else {
                val files = mutableMapOf<Int, Int>()
                val cards =
                    candidates.map {
                        files[it.bookId] = it.file.id
                        BookCard(it.bookId, it.title, it.authors)
                    }
                sortLocalBooks(cards, sort)
                    .asSequence()
                    .drop((page - 1) * 30)
                    .take(30)
                    .mapNotNull { files[it.id]?.let { fileId -> book(fileId) } }
                    .toList()
            }
        }

    fun recentlyRead(): List<SavedEbook> =
        synchronized(lock) {
            root
                .listFiles()
                .orEmpty()
                .asSequence()
                .filter { it.isDirectory && File(it, "place.json").isFile }
                .sortedByDescending { File(it, "place.json").lastModified() }
                .mapNotNull { dir ->
                    dir.name.toIntOrNull()?.let { id ->
                        runCatching {
                                book(id)?.takeIf { ready(it.file) && place(it.file) != null }
                            }
                            .getOrNull()
                    }
                }
                .distinctBy { it.bookId }
                .take(10)
                .toList()
        }

    fun preferredFile(bookId: Int): Int? =
        synchronized(lock) { read(File(root, "preferred-$bookId"))?.toIntOrNull() }

    fun savePreferredFile(bookId: Int, fileId: Int) =
        synchronized(lock) { write(File(root, "preferred-$bookId"), fileId.toString()) }

    fun savePlace(fileId: Int, place: ReadingPlace) =
        synchronized(lock) {
            require(place.locator.length <= 64_000)
            write(File(dir(fileId), "place.json"), wireJson.encodeToString(place))
        }

    fun place(file: EbookFile): ReadingPlace? =
        synchronized(lock) {
            read(File(dir(file.id), "place.json"))
                ?.let { wireJson.decodeFromString<ReadingPlace>(it) }
                ?.takeIf { it.revision == file.revision }
        }

    fun preferences(): ReadingPreferences =
        synchronized(lock) {
            read(File(root, "preferences.json"))?.let {
                wireJson.decodeFromString<ReadingPreferences>(it).validated()
            } ?: ReadingPreferences()
        }

    fun savePreferences(value: ReadingPreferences) =
        synchronized(lock) {
            write(File(root, "preferences.json"), wireJson.encodeToString(value.validated()))
        }

    fun removeContent(file: EbookFile) =
        synchronized(lock) {
            dir(file.id)
                .listFiles()
                .orEmpty()
                .filter {
                    it.name.endsWith(".epub") ||
                        it.name.endsWith(".pdf") ||
                        it.name.endsWith(".cbz") ||
                        it.name.endsWith(".part")
                }
                .forEach {
                    if (!it.delete() && it.exists())
                        throw IOException("Could not remove this ebook.")
                }
        }

    private fun read(file: File): String? =
        if (file.exists() || File(file.path + ".bak").exists())
            AtomicFile(file).openRead().bufferedReader().use { it.readText() }
        else null

    private fun write(file: File, value: String) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(value.toByteArray())
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }
}

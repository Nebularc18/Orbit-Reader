package app.bookorbit.community.data

import kotlinx.serialization.Serializable

val wireJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

@Serializable
data class Account(
    val id: Int,
    val name: String = "",
    val username: String,
    val isSuperuser: Boolean = false,
    val permissions: List<String> = emptyList(),
)

@Serializable
data class Credentials(val accessToken: String, val refreshToken: String, val user: Account)

@Serializable data class Tokens(val accessToken: String, val refreshToken: String)

@Serializable
data class Session(
    val server: String,
    val accessToken: String,
    val refreshToken: String,
    val user: Account,
) {
    val scope: String
        get() =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest("$server|${user.id}".toByteArray())
                .joinToString("") { "%02x".format(it) }

    val canDownload: Boolean
        get() = user.isSuperuser || "library_download" in user.permissions
}

@Serializable
data class BookCard(
    val id: Int,
    val title: String? = null,
    val authors: List<String> = emptyList(),
    val readingProgress: Double? = null,
    val coverVersion: String = "",
    val files: List<EbookFile> = emptyList(),
    val seriesName: String? = null,
    @kotlinx.serialization.Transient val localDownload: LocalDownloadStatus? = null,
)

@Serializable
data class BooksPage(val items: List<BookCard>, val total: Int, val page: Int, val size: Int)

@Serializable
data class AudioBook(
    val id: Int,
    val title: String,
    val authors: List<String> = emptyList(),
    val narrators: List<String> = emptyList(),
    val hasCover: Boolean = false,
)

@Serializable
data class AudioAsset(
    val assetId: String,
    val fileId: Int,
    val sequence: Int,
    val format: String,
    val durationMs: Long? = null,
    val sizeBytes: Long? = null,
    val etag: String,
)

@Serializable
data class AudioChapter(
    val id: String,
    val title: String,
    val assetId: String,
    val sequence: Int,
    val startMs: Long,
    val endMs: Long,
    val assetOffsetMs: Long,
)

@Serializable
data class AudioManifest(
    val schema: String,
    val schemaVersion: Int,
    val revision: String,
    val book: AudioBook,
    val assets: List<AudioAsset>,
    val chapters: List<AudioChapter>,
    val totalDurationMs: Long,
) {
    fun validated(): AudioManifest {
        require(schema == "bookorbit.audiobook-manifest" && schemaVersion == 2) {
            "This server's audiobook API is not supported yet."
        }
        require(assets.isNotEmpty() && assets.map { it.assetId }.distinct().size == assets.size)
        require(assets.all { it.assetId.matches(Regex("aud_[0-9a-fA-F-]{36}")) && it.fileId > 0 })
        return copy(
            assets = assets.sortedBy { it.sequence },
            chapters = chapters.sortedBy { it.sequence },
        )
    }
}

@Serializable
data class PlaybackState(
    val assetId: String,
    val positionMs: Long,
    val capturedAt: String,
    val revision: Int,
    val manifestRevision: String,
)

@Serializable
data class ProgressWrite(
    val assetId: String,
    val positionMs: Long,
    val capturedAt: String,
    val operationId: String,
    val baseRevision: Int,
    val manifestRevision: String,
)

@Serializable
data class LocalPosition(
    val assetId: String,
    val positionMs: Long,
    val capturedAt: String,
    val revision: Int,
    val manifestRevision: String,
    val dirty: Boolean = true,
    val operationId: String = java.util.UUID.randomUUID().toString(),
) {
    fun write() =
        ProgressWrite(assetId, positionMs, capturedAt, operationId, revision, manifestRevision)
}

@Serializable data class EbookAuthor(val name: String)

@Serializable
data class EbookFile(
    val id: Int,
    val format: String? = null,
    val filename: String? = null,
    val role: String = "",
    val sizeBytes: Long? = null,
    val fileHash: String? = null,
    val contentVersion: String? = null,
    val createdAt: String = "",
) {
    val supported: Boolean
        get() = format?.lowercase() in listOf("epub", "kepub", "pdf", "cbz", "cbr", "cb7")

    val revision: String
        get() = fileHash ?: contentVersion ?: "$id-$sizeBytes-$createdAt"
}

@Serializable
data class EbookDetail(
    val id: Int,
    val title: String? = null,
    val authors: List<EbookAuthor> = emptyList(),
    val files: List<EbookFile> = emptyList(),
    val subtitle: String? = null,
    val description: String? = null,
    val seriesName: String? = null,
    val seriesIndex: String? = null,
    val publisher: String? = null,
    val publishedDate: String? = null,
    val publishedYear: Int? = null,
    val language: String? = null,
    val pageCount: Int? = null,
    val isbn10: String? = null,
    val isbn13: String? = null,
    val rating: Double? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val libraryName: String? = null,
    val coverVersion: String = "",
    val personalNote: String? = null,
    val collections: List<EbookAuthor> = emptyList(),
    val readStatus: BookReadStatus? = null,
)

@Serializable data class BookReadStatus(val status: String)

@Serializable
data class EbookProgress(
    val percentage: Double = 0.0,
    val cfi: String? = null,
    val pageNumber: Int? = null,
    val textUpdatedAt: String? = null,
)

@Serializable
data class EbookProgressWrite(
    val percentage: Double,
    val pageNumber: Int? = null,
    val cfi: String? = null,
    val source: String = "text",
)

@Serializable data class ComicPages(val pageCount: Int)

@Serializable
data class AnnotationRect(val x: Double, val y: Double, val width: Double, val height: Double)

@Serializable
data class AnnotationPdf(val page: Int, val rect: AnnotationRect, val rects: List<AnnotationRect>)

@Serializable
data class BookAnnotation(
    val id: Int,
    val bookId: Int,
    val cfi: String? = null,
    val jumpFileId: Int? = null,
    val text: String,
    val note: String? = null,
    val color: String = "#ffff00",
    val style: String = "highlight",
    val pdf: AnnotationPdf? = null,
)

@Serializable
data class AnnotationPage(val items: List<BookAnnotation>, val total: Int, val page: Int)

@Serializable
data class AnnotationCreate(
    val bookFileId: Int,
    val text: String,
    val cfi: String? = null,
    val pdf: AnnotationPdf? = null,
    val color: String = "#ffff00",
    val style: String = "highlight",
    val note: String? = null,
    val chapterTitle: String? = null,
)

@Serializable data class AnnotationEdit(val note: String?)

@Serializable data class DashboardShelf(val books: List<BookCard> = emptyList())

data class LocalDownloadStatus(val bytes: Long, val complete: Boolean)

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

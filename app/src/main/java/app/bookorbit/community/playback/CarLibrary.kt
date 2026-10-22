package app.bookorbit.community.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.*

const val CAR_ROOT = "orbit-library"

fun carBookId(owner: String, id: Int) = "orbit-book:$owner:$id"

fun parseCarBookId(value: String, owner: String): Int? =
    value
        .removePrefix("orbit-book:$owner:")
        .takeIf { value.startsWith("orbit-book:$owner:") }
        ?.toIntOrNull()
        ?.takeIf { it > 0 }

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CarLibrary(private val app: OrbitApplication) {
    fun folder(id: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()

    fun root(): MediaItem =
        folder(
            CAR_ROOT,
            app.sessions.read()?.let { "Orbit Reader · ${android.net.Uri.parse(it.server).host}" }
                ?: "Open Orbit Reader to sign in",
        )

    fun children(parent: String, page: Int, size: Int, query: String = ""): List<MediaItem> {
        require(page in 0..10000 && size in 1..100)
        val account = app.sessions.read() ?: throw ApiFailure(401)
        if (parent == CAR_ROOT)
            return if (page == 0)
                listOf(
                    folder("orbit-continue", "Continue listening"),
                    folder("orbit-downloads", "Downloaded books"),
                    folder("orbit-queue", "Listening queue"),
                    folder("orbit-all", "All audiobooks"),
                )
            else emptyList()
        val start = page * size
        val cards: List<BookCard> =
            when (parent) {
                "orbit-continue" ->
                    app.api
                        .dashboard("continue-listening", account.scope)
                        .books
                        .drop(start)
                        .take(size)
                "orbit-queue" ->
                    PlaybackToolsStorage(app, account.scope).queue().drop(start).take(size).map {
                        BookCard(it.id, it.title, it.authors)
                    }
                "orbit-all",
                "orbit-search",
                "orbit-downloads" -> {
                    val first = start / 30 + 1
                    val last = (start + size - 1) / 30 + 1
                    (first..last)
                        .flatMap { p ->
                            if (parent == "orbit-downloads")
                                AudioStorage(app, account.scope).downloadedBooks(p).map {
                                    BookCard(it.book.id, it.book.title, it.book.authors)
                                }
                            else app.api.books(p, query, owner = account.scope).items
                        }
                        .drop(start % 30)
                        .take(size)
                }
                else -> throw IllegalArgumentException("Unknown library folder")
            }
        check(app.sessions.read()?.scope == account.scope)
        return cards.map { book ->
            MediaItem.Builder()
                .setMediaId(carBookId(account.scope, book.id))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(book.title ?: "Untitled")
                        .setArtist(book.authors.joinToString(", "))
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build()
                )
                .build()
        }
    }
}

@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package app.bookorbit.community.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.EbookProgressWrite
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.resource.TransformingContainer
import org.readium.r2.shared.util.resource.map
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

data class EpubReaderState(
    val book: SavedEbook? = null,
    val publication: Publication? = null,
    val initialLocator: Locator? = null,
    val preferences: ReadingPreferences = ReadingPreferences(),
    val error: String? = null,
    val loading: Boolean = true,
)

class EpubReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OrbitApplication
    private val mutable = MutableStateFlow(EpubReaderState())
    val state = mutable.asStateFlow()
    private var owner = ""
    private var fileId = 0
    private var loading: Job? = null
    private val persistence = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    fun load(account: String, file: Int, noteId: String? = null) {
        if (loading != null) return
        owner = account
        fileId = file
        loading =
            viewModelScope.launch {
                try {
                    val loaded =
                        withContext(Dispatchers.IO) {
                            check(app.sessions.read()?.scope == owner)
                            val storage = EbookStorage(app, owner)
                            val book = storage.book(fileId) ?: throw IOException()
                            prepareAutomaticReadingSync(app, owner, book.file)
                            require(storage.ready(book.file)) {
                                "Open this book from the Read tab to save an offline copy first."
                            }
                            val note =
                                noteId?.let {
                                    ReadingNotes(app, owner, fileId).all().firstOrNull { note ->
                                        note.id == it && note.revision == book.file.revision
                                    } ?: error("Saved location unavailable")
                                }
                            val locator =
                                (note?.let { ReadingPlace(it.revision, it.locator) }
                                        ?: storage.place(book.file))
                                    ?.let {
                                        runCatching { Locator.fromJSON(JSONObject(it.locator)) }
                                            .getOrNull()
                                    }
                            require(noteId == null || locator != null) {
                                "Saved location unavailable"
                            }
                            val http = DefaultHttpClient()
                            val retriever = AssetRetriever(app.contentResolver, http)
                            val asset =
                                retriever.retrieve(storage.content(book.file).toUrl()).getOrElse {
                                    throw IOException()
                                }
                            val opener =
                                PublicationOpener(
                                    DefaultPublicationParser(
                                        app,
                                        http,
                                        retriever,
                                        pdfFactory = null,
                                    )
                                )
                            val publication =
                                opener
                                    .open(
                                        asset,
                                        allowUserInteraction = false,
                                        onCreatePublication = {
                                            val html =
                                                (manifest.readingOrder + manifest.resources)
                                                    .filter {
                                                        it.mediaType
                                                            ?.toString()
                                                            ?.contains("html") == true
                                                    }
                                                    .map { it.href.toString() }
                                                    .toSet()
                                            container =
                                                TransformingContainer(container) { url, resource ->
                                                    val path =
                                                        url.toString()
                                                            .substringBefore('#')
                                                            .substringBefore('?')
                                                    if (
                                                        path in html ||
                                                            path
                                                                .substringAfterLast('.')
                                                                .lowercase() in
                                                                listOf("html", "htm", "xhtml")
                                                    )
                                                        resource.map {
                                                            Try.success(EpubContent.sanitize(it))
                                                        }
                                                    else if (
                                                        path.substringAfterLast('.').lowercase() ==
                                                            "svg"
                                                    )
                                                        resource.map {
                                                            Try.success(EpubContent.sanitizeSvg(it))
                                                        }
                                                    else resource
                                                }
                                        },
                                    )
                                    .getOrElse { throw IOException() }
                            if (publication.isRestricted) {
                                publication.close()
                                throw IllegalArgumentException(
                                    "Encrypted EPUBs are not supported yet."
                                )
                            }
                            EpubReaderState(
                                book,
                                publication,
                                locator,
                                storage.preferences(),
                                loading = false,
                            )
                        }
                    if (app.sessions.read()?.scope != owner) {
                        loaded.publication?.close()
                        throw IOException()
                    }
                    mutable.value = loaded
                    syncOnExit()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    mutable.update {
                        it.copy(
                            loading = false,
                            error =
                                if (error is IllegalArgumentException) error.message
                                else "Could not open this EPUB. Return to Read and retry.",
                        )
                    }
                }
            }
    }

    private fun cfiBridge(): EpubCfi? {
        val book = mutable.value.book ?: return null
        if (app.sessions.read()?.scope != owner) return null
        return EpubCfi(EbookStorage(app, owner).content(book.file))
    }

    suspend fun cfi(locator: Locator): String? =
        withContext(Dispatchers.IO) { cfiBridge()?.fromLocator(locator) }

    suspend fun locator(cfi: String): Locator? =
        withContext(Dispatchers.IO) { cfiBridge()?.toLocator(cfi) }

    fun saveLocation(locator: Locator) {
        val book = mutable.value.book ?: return
        mutable.update { it.copy(initialLocator = locator) }
        val place = ReadingPlace(book.file.revision, locator.toJSON().toString())
        persist {
            savePlace(fileId, place)
            locator.locations.totalProgression?.let { progression ->
                checkpointReadingUpload(
                    book.file,
                    EbookProgressWrite((progression * 100).coerceIn(0.0, 100.0)),
                    locator = place.locator,
                )
                enqueueAutomaticReadingSync(app, owner, fileId, flush = false)
            }
        }
    }

    fun syncOnExit() {
        val previous = saveJob
        saveJob =
            persistence.launch {
                previous?.join()
                try {
                    enqueueAutomaticReadingSync(app, owner, fileId)
                } catch (_: Exception) {
                    message("Your position is saved locally. Open the book again to retry sync.")
                }
            }
    }

    fun preferences(value: ReadingPreferences) {
        value.validated()
        mutable.update { it.copy(preferences = value) }
        persist { savePreferences(value) }
    }

    fun message(value: String) {
        mutable.update { it.copy(error = value) }
    }

    private fun persist(action: EbookStorage.() -> Unit) {
        val previous = saveJob
        saveJob =
            persistence.launch {
                previous?.join()
                try {
                    if (app.sessions.read()?.scope == owner) EbookStorage(app, owner).action()
                } catch (_: Exception) {
                    message(
                        "Could not save your reading position or preferences. Check device storage."
                    )
                }
            }
    }

    override fun onCleared() {
        runBlocking { saveJob?.join() }
        persistence.cancel()
        mutable.value.publication?.close()
        super.onCleared()
    }
}

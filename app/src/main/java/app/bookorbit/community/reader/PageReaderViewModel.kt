package app.bookorbit.community.reader

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.wireJson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

data class PageReaderState(
    val book: SavedEbook? = null,
    val bitmap: Bitmap? = null,
    val page: Int = 0,
    val count: Int = 0,
    val busy: Boolean = true,
    val pageWidth: Double = 1.0,
    val pageHeight: Double = 1.0,
    val error: String? = null,
    val selection: PdfTextSelection? = null,
    val notice: String? = null,
)

class PageReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OrbitApplication
    private var owner = ""
    private var document: PagedDocument? = null
    private val lock = Mutex()
    private var job: Job? = null
    private val mutable = MutableStateFlow(PageReaderState())
    val state = mutable.asStateFlow()

    fun load(scope: String, fileId: Int, noteId: String? = null) {
        if (job != null) return
        owner = scope
        job =
            viewModelScope.launch {
                try {
                    val pair =
                        withContext(Dispatchers.IO) {
                            lock.withLock {
                                check(app.sessions.read()?.scope == owner)
                                val storage = EbookStorage(app, owner)
                                val book = storage.book(fileId) ?: error("No saved book")
                                val note =
                                    noteId?.let {
                                        ReadingNotes(app, owner, fileId).all().firstOrNull { note ->
                                            note.id == it && note.revision == book.file.revision
                                        } ?: error("Saved location unavailable")
                                    }
                                document =
                                    PagedDocument(
                                        storage.content(book.file),
                                        book.file.format?.lowercase() == "pdf",
                                    )
                                val page =
                                    (note?.let { ReadingPlace(it.revision, it.locator) }
                                            ?: storage.place(book.file))
                                        ?.let {
                                            runCatching {
                                                    wireJson
                                                        .decodeFromString<PageLocation>(it.locator)
                                                        .page
                                                }
                                                .getOrNull()
                                        }
                                        ?: if (note != null) error("Saved location unavailable")
                                        else 0
                                book to page.coerceIn(0, document!!.pageCount - 1)
                            }
                        }
                    mutable.value = PageReaderState(book = pair.first, count = document!!.pageCount)
                    render(pair.second)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    mutable.update {
                        it.copy(
                            busy = false,
                            error =
                                "Could not open this document. Encrypted PDFs and unsupported image codecs cannot be read.",
                        )
                    }
                }
            }
    }

    fun go(page: Int) {
        if (mutable.value.busy || page !in 0 until mutable.value.count) return
        job = viewModelScope.launch { render(page) }
    }

    fun selectText(page: Int, ax: Double, ay: Double, bx: Double, by: Double) {
        if (mutable.value.busy || mutable.value.page != page) return
        mutable.update { it.copy(busy = true, selection = null, error = null) }
        job =
            viewModelScope.launch {
                try {
                    val result =
                        withContext(Dispatchers.IO) {
                            lock.withLock {
                                check(app.sessions.read()?.scope == owner)
                                document!!.selectText(page, ax, ay, bx, by)
                            }
                        }
                    mutable.update {
                        it.copy(
                            busy = false,
                            selection = result,
                            notice =
                                if (result == null)
                                    "No selectable text here, or the selection is too large. Try a word or use Highlight area for scanned pages."
                                else null,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    mutable.update {
                        it.copy(
                            busy = false,
                            notice =
                                "Text selection is unavailable for this page. Try Highlight area.",
                        )
                    }
                }
            }
    }

    fun clearSelection() {
        mutable.update { it.copy(selection = null, notice = null) }
    }

    fun clearNotice(value: String) {
        mutable.update { if (it.notice == value) it.copy(notice = null) else it }
    }

    private suspend fun render(page: Int) {
        mutable.update { it.copy(busy = true, error = null, selection = null) }
        try {
            val bitmap =
                withContext(Dispatchers.IO) {
                    lock.withLock {
                        check(app.sessions.read()?.scope == owner)
                        val bitmap = document!!.render(page)
                        val book = mutable.value.book!!
                        EbookStorage(app, owner)
                            .savePlace(
                                book.file.id,
                                ReadingPlace(
                                    book.file.revision,
                                    wireJson.encodeToString(PageLocation(page)),
                                ),
                            )
                        bitmap to document!!.dimensions(page)
                    }
                }
            mutable.update {
                it.copy(
                    bitmap = bitmap.first,
                    page = page,
                    busy = false,
                    pageWidth = bitmap.second.first,
                    pageHeight = bitmap.second.second,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutable.update {
                it.copy(
                    busy = false,
                    error =
                        "Could not display this page. Try another page or download the document again.",
                )
            }
        }
    }

    override fun onCleared() {
        runBlocking { lock.withLock { document?.close() } }
        super.onCleared()
    }
}

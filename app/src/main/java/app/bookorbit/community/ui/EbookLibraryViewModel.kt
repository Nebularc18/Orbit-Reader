package app.bookorbit.community.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.*
import app.bookorbit.community.reader.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock

data class EbookShelfState(
    val books: List<BookCard> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val query: String = "",
    val offline: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val selected: EbookDetail? = null,
    val options: Boolean = false,
    val readyFiles: Set<Int> = emptySet(),
    val readyToRead: Boolean = false,
    val saved: List<SavedEbook> = emptyList(),
    val preparing: SavedEbook? = null,
    val workId: String? = null,
)

class EbookLibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OrbitApplication
    val owner = app.sessions.read()?.scope ?: ""
    private val mutable = MutableStateFlow(EbookShelfState())
    val state = mutable.asStateFlow()
    private var listing: Job? = null
    private var selection: Job? = null

    init {
        browse()
    }

    private fun checkAccount() {
        check(app.sessions.read()?.scope == owner)
    }

    fun search(query: String) {
        mutable.update { it.copy(query = query, page = 1) }
        browse(true)
    }

    fun page(delta: Int) {
        mutable.update { it.copy(page = (it.page + delta).coerceAtLeast(1)) }
        browse()
    }

    fun offline(value: Boolean) {
        mutable.update { it.copy(offline = value, page = 1, query = "", books = emptyList()) }
        browse()
    }

    fun browse(debounce: Boolean = false) {
        listing?.cancel()
        listing =
            viewModelScope.launch {
                if (debounce) delay(350)
                val snapshot = mutable.value
                mutable.update { it.copy(busy = true, error = null) }
                try {
                    val (books, saved) =
                        withContext(Dispatchers.IO) {
                            checkAccount()
                            if (snapshot.offline) {
                                val saved = EbookStorage(app, owner).books(snapshot.page)
                                BooksPage(
                                    saved
                                        .map { BookCard(it.bookId, it.title, it.authors) }
                                        .distinctBy { it.id },
                                    -1,
                                    snapshot.page,
                                    30,
                                ) to saved
                            } else
                                app.api.books(snapshot.page, snapshot.query, ebooks = true) to
                                    emptyList()
                        }
                    checkAccount()
                    mutable.update {
                        it.copy(
                            books = books.items,
                            saved = saved,
                            total = books.total,
                            busy = false,
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    mutable.update {
                        it.copy(
                            busy = false,
                            error =
                                "Could not load ebooks. Check your connection or open On device.",
                        )
                    }
                }
            }
    }

    fun select(bookId: Int, openDirectly: Boolean = false) {
        selection?.cancel()
        selection =
            viewModelScope.launch {
                mutable.update { it.copy(busy = true, error = null) }
                try {
                    val detail =
                        withContext(Dispatchers.IO) {
                            checkAccount()
                            if (mutable.value.offline) {
                                val saved = mutable.value.saved.filter { it.bookId == bookId }
                                val first = saved.first()
                                EbookDetail(
                                    bookId,
                                    first.title,
                                    first.authors.map(::EbookAuthor),
                                    saved.map { it.file },
                                )
                            } else {
                                try {
                                    app.api.ebookDetail(bookId, owner)
                                } catch (error: ApiFailure) {
                                    throw error
                                } catch (error: java.io.IOException) {
                                    val saved =
                                        EbookStorage(app, owner).recentlyRead().firstOrNull {
                                            it.bookId == bookId
                                        } ?: throw error
                                    EbookDetail(
                                        bookId,
                                        saved.title,
                                        saved.authors.map(::EbookAuthor),
                                        listOf(saved.file),
                                    )
                                }
                            }
                        }
                    val readyFiles =
                        withContext(Dispatchers.IO) {
                            val storage = EbookStorage(app, owner)
                            detail.files
                                .filter { it.supported && storage.ready(it) }
                                .map { it.id }
                                .toSet()
                        }
                    checkAccount()
                    require(detail.files.any { it.supported })
                    mutable.update {
                        it.copy(
                            selected = detail,
                            options = !openDirectly,
                            readyFiles = readyFiles,
                            readyToRead = false,
                            busy = false,
                        )
                    }
                    if (openDirectly) {
                        val preferred =
                            withContext(Dispatchers.IO) {
                                EbookStorage(app, owner).preferredFile(bookId)
                            }
                        val file = preferredReadingFile(detail.files, preferred)
                        file?.let(::prepare)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    mutable.update {
                        it.copy(
                            busy = false,
                            error =
                                "Could not open this book. EPUB, KEPUB, PDF, CBZ, CBR, and CB7 files are supported.",
                        )
                    }
                }
            }
    }

    fun closeDetail() {
        mutable.update {
            it.copy(selected = null, preparing = null, options = false, readyToRead = false)
        }
    }

    fun prepare(file: EbookFile) {
        val detail = mutable.value.selected ?: return
        viewModelScope.launch {
            try {
                val book =
                    SavedEbook(
                        detail.id,
                        detail.title ?: "Untitled",
                        detail.authors.map { it.name },
                        file,
                    )
                val (workId, ready) =
                    withContext(Dispatchers.IO) {
                        checkAccount()
                        app.downloadLock(owner, -file.id).withLock {
                            EbookStorage(app, owner).saveBook(book)
                            EbookStorage(app, owner).savePreferredFile(detail.id, file.id)
                            val ready = EbookStorage(app, owner).ready(file)
                            (if (ready) null else enqueueEbook(app, owner, file.id)) to ready
                        }
                    }
                mutable.update {
                    if (it.selected == detail)
                        it.copy(
                            preparing = book,
                            workId = workId,
                            readyToRead = ready,
                            error = null,
                        )
                    else it
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutable.update { it.copy(error = "Could not prepare this ebook. Try again.") }
            }
        }
    }

    fun removed(file: EbookFile) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    checkAccount()
                    WorkManager.getInstance(app)
                        .cancelUniqueWork("ebook-$owner-${file.id}")
                        .result
                        .get()
                    app.downloadLock(owner, -file.id).withLock {
                        EbookStorage(app, owner).removeContent(file)
                    }
                }
                closeDetail()
                browse()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutable.update { it.copy(error = "Could not remove this ebook. Try again.") }
            }
        }
    }

    fun opened() {
        mutable.update { it.copy(preparing = null, selected = null, readyToRead = false) }
    }
}

fun preferredReadingFile(files: List<EbookFile>, preferredId: Int?): EbookFile? {
    val supported = files.filter { it.supported }
    return supported.firstOrNull { it.id == preferredId }
        ?: supported.minByOrNull {
            listOf("epub", "kepub", "pdf", "cbz", "cbr", "cb7").indexOf(it.format?.lowercase())
        }
}

package app.bookorbit.community.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.*
import androidx.work.WorkManager
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.*
import app.bookorbit.community.reader.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock

data class EbookShelfState(
    val query: String = "",
    val offline: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val selected: EbookDetail? = null,
    val options: Boolean = false,
    val readyFiles: Set<Int> = emptySet(),
    val readyToRead: Boolean = false,
    val preparing: SavedEbook? = null,
    val workId: String? = null,
)

class EbookLibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OrbitApplication
    val owner = app.sessions.read()?.scope ?: ""
    private val mutable = MutableStateFlow(EbookShelfState())
    val state = mutable.asStateFlow()
    private val refresh = MutableStateFlow(0)
    private var selection: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val catalog =
        combine(mutable.map { it.query to it.offline }.distinctUntilChanged(), refresh) {
                key,
                generation ->
                key to generation
            }
            .debounce(300)
            .flatMapLatest { (key, _) ->
                bookPager { page ->
                        withContext(Dispatchers.IO) {
                            checkAccount()
                            val result =
                                if (!key.second)
                                    app.api.books(page, key.first, ebooks = true, owner = owner)
                                else {
                                    val saved = EbookStorage(app, owner).shelfBooks(page)
                                    BooksPage(
                                        saved.map { BookCard(it.bookId, it.title, it.authors) },
                                        -1,
                                        page,
                                        30,
                                    )
                                }
                            checkAccount()
                            result
                        }
                    }
                    .flow
            }
            .cachedIn(viewModelScope)

    private fun checkAccount() {
        check(app.sessions.read()?.scope == owner)
    }

    fun search(query: String) {
        mutable.update { it.copy(query = query, error = null) }
    }

    fun offline(value: Boolean) {
        mutable.update { it.copy(offline = value, query = "", error = null) }
    }

    fun browse() {
        mutable.update { it.copy(error = null) }
        refresh.value += 1
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
                                val saved = EbookStorage(app, owner).filesForBook(bookId)
                                val first = saved.first()
                                BookDetailsStorage(app, owner)
                                    .read(bookId)
                                    ?.copy(files = saved.map { it.file })
                                    ?: EbookDetail(
                                        bookId,
                                        first.title,
                                        first.authors.map(::EbookAuthor),
                                        saved.map { it.file },
                                    )
                            } else {
                                try {
                                    app.api.ebookDetail(bookId, owner).also {
                                        runCatching { BookDetailsStorage(app, owner).save(it) }
                                    }
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

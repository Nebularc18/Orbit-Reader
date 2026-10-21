package app.bookorbit.community.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.*
import androidx.work.WorkManager
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.*
import app.bookorbit.community.download.enqueueDownload
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock

data class LibraryState(
    val session: Session? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val query: String = "",
    val offline: Boolean = false,
    val selected: AudioManifest? = null,
    val details: EbookDetail? = null,
    val detailsBusy: Boolean = false,
    val detailsError: String? = null,
    val downloaded: Boolean = false,
    val wifiOnly: Boolean = true,
    val usedBytes: Long = 0,
    val availableBytes: Long = 0,
    val selectedBytes: Long = 0,
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OrbitApplication
    private val mutable = MutableStateFlow(LibraryState(session = app.sessions.read()))
    val state = mutable.asStateFlow()
    private val refresh = MutableStateFlow(0)
    private var detailJob: Job? = null
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val catalog =
        combine(
                mutable
                    .map { Triple(it.session?.scope, it.query, it.offline) }
                    .distinctUntilChanged(),
                refresh,
            ) { key, generation ->
                key to generation
            }
            .debounce(300)
            .flatMapLatest { (key, _) ->
                val owner = key.first
                if (owner == null) flowOf(PagingData.empty())
                else
                    bookPager { page ->
                            withContext(Dispatchers.IO) {
                                if (app.sessions.read()?.scope != owner) throw ApiFailure(401)
                                val result =
                                    if (!key.third) app.api.books(page, key.second, owner = owner)
                                    else {
                                        val downloads = AudioStorage(app, owner).downloads(page)
                                        BooksPage(
                                            downloads.map {
                                                BookCard(
                                                    it.manifest.book.id,
                                                    it.manifest.book.title,
                                                    it.manifest.book.authors,
                                                    localDownload =
                                                        LocalDownloadStatus(it.bytes, it.complete),
                                                )
                                            },
                                            -1,
                                            page,
                                            30,
                                        )
                                    }
                                if (app.sessions.read()?.scope != owner) throw ApiFailure(401)
                                result
                            }
                        }
                        .flow
            }
            .cachedIn(viewModelScope)

    fun login(server: String, username: String, password: String) {
        if (mutable.value.busy) return
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null) }
            try {
                val session =
                    withContext(Dispatchers.IO) { app.api.login(server, username, password) }
                mutable.value = LibraryState(session = session)
                browse()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutable.update { it.copy(busy = false, error = friendly(error)) }
            }
        }
    }

    fun logout(onStopped: () -> Unit) {
        detailJob?.cancel()
        onStopped()
        val session = app.sessions.read() ?: return
        WorkManager.getInstance(app).cancelAllWorkByTag("account-${session.scope}")
        app.currentManifest.value = null
        mutable.value = LibraryState(busy = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { app.api.logout() }
            } catch (_: IOException) {} finally {
                mutable.value = LibraryState()
            }
        }
    }

    fun search(value: String) {
        mutable.update { it.copy(query = value, error = null) }
    }

    fun setOffline(value: Boolean) {
        mutable.update { it.copy(offline = value, query = "", error = null) }
        refreshDownloaded()
    }

    fun browse() {
        mutable.update { it.copy(error = null) }
        refresh.value += 1
        refreshDownloaded()
    }

    fun select(bookId: Int) {
        detailJob?.cancel()
        val account = mutable.value.session ?: return
        detailJob =
            viewModelScope.launch {
                mutable.update {
                    it.copy(
                        busy = true,
                        error = null,
                        selected = null,
                        details = null,
                        detailsBusy = false,
                        detailsError = null,
                    )
                }
                try {
                    val storage = AudioStorage(app, account.scope)
                    val loaded =
                        withContext(Dispatchers.IO) {
                            if (app.sessions.read()?.scope != account.scope) throw ApiFailure(401)
                            val manifest =
                                try {
                                    app.api.manifest(bookId, account.scope)
                                } catch (error: ApiFailure) {
                                    throw error
                                } catch (error: IOException) {
                                    storage.manifest(bookId) ?: throw error
                                }
                            Triple(
                                manifest,
                                storage.downloaded(manifest),
                                storage.audioBytes(bookId),
                            )
                        }
                    val cachedDetails =
                        withContext(Dispatchers.IO) {
                            BookDetailsStorage(app, account.scope).read(bookId)
                        }
                    if (app.sessions.read()?.scope != account.scope) return@launch
                    mutable.update {
                        it.copy(
                            selected = loaded.first,
                            details = cachedDetails,
                            downloaded = loaded.second,
                            selectedBytes = loaded.third,
                            busy = false,
                            detailsBusy = true,
                        )
                    }
                    val details =
                        withContext(Dispatchers.IO) {
                            val cache = BookDetailsStorage(app, account.scope)
                            try {
                                app.api.ebookDetail(bookId, account.scope).also {
                                    runCatching { cache.save(it) }
                                } to false
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: IOException) {
                                cache.read(bookId) to true
                            }
                        }
                    mutable.update {
                        if (it.session?.scope == account.scope && it.selected?.book?.id == bookId)
                            it.copy(
                                details = details.first,
                                detailsBusy = false,
                                detailsError =
                                    if (details.second)
                                        "Could not refresh book information. Check your connection and reopen this book to retry."
                                    else null,
                            )
                        else it
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    mutable.update {
                        it.copy(busy = false, detailsBusy = false, error = friendly(error))
                    }
                }
            }
    }

    fun closeDetail() {
        detailJob?.cancel()
        mutable.update {
            it.copy(selected = null, details = null, detailsBusy = false, detailsError = null)
        }
    }

    fun wifiOnly(value: Boolean) {
        mutable.update { it.copy(wifiOnly = value) }
    }

    fun download() {
        val snapshot = mutable.value
        val account = snapshot.session ?: return
        val book = snapshot.selected ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    app.downloadLock(account.scope, book.book.id).withLock {
                        if (app.sessions.read()?.scope == account.scope) {
                            AudioStorage(app, account.scope).markDownload(book)
                            enqueueDownload(app, account, book.book.id, snapshot.wifiOnly)
                        }
                    }
                }
                refreshDownloads()
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                mutable.update {
                    it.copy(
                        error = "Could not start the download. Check device storage and try again."
                    )
                }
            }
        }
    }

    fun refreshDownloaded() {
        viewModelScope.launch {
            try {
                refreshDownloads()
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                mutable.update {
                    it.copy(error = "Could not read downloaded audio on this device.")
                }
            }
        }
    }

    private suspend fun refreshDownloads() {
        val snapshot = mutable.value
        val account = snapshot.session ?: return
        if (!snapshot.offline && snapshot.selected == null) return
        withContext(Dispatchers.IO) {
            val storage = AudioStorage(app, account.scope)
            val used = storage.usedBytes()
            val available = storage.availableBytes()
            val complete = snapshot.selected?.let(storage::downloaded) ?: false
            val selectedBytes = snapshot.selected?.let { storage.audioBytes(it.book.id) } ?: 0
            mutable.update {
                if (
                    it.session?.scope != account.scope ||
                        it.offline != snapshot.offline ||
                        it.selected != snapshot.selected
                )
                    it
                else
                    it.copy(
                        usedBytes = used,
                        availableBytes = available,
                        downloaded = complete,
                        selectedBytes = selectedBytes,
                    )
            }
        }
    }

    fun removeDownload() {
        val snapshot = mutable.value
        val account = snapshot.session ?: return
        val bookId = snapshot.selected?.book?.id ?: return
        if (app.currentManifest.value?.book?.id == bookId) return
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null) }
            try {
                withContext(Dispatchers.IO) {
                    WorkManager.getInstance(app)
                        .cancelUniqueWork("download-${account.scope}-$bookId")
                        .result
                        .get()
                    app.downloadLock(account.scope, bookId).withLock {
                        if (app.currentManifest.value?.book?.id != bookId)
                            AudioStorage(app, account.scope).deleteBook(bookId)
                    }
                }
                mutable.update {
                    if (it.session?.scope == account.scope)
                        it.copy(downloaded = false, selected = null, busy = false)
                    else it
                }
                if (snapshot.offline && mutable.value.session?.scope == account.scope) browse()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutable.update {
                    it.copy(busy = false, error = "Could not remove the download. Try again.")
                }
            }
        }
    }

    private fun friendly(error: Exception): String =
        when (error) {
            is ApiFailure -> error.message!!
            is IllegalArgumentException ->
                "Enter a valid server URL, such as https://books.example.com."
            else ->
                "Cannot reach your server. Check the address and connection, or open Downloads for offline listening."
        }
}

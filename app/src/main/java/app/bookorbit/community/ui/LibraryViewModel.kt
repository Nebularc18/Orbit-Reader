package app.bookorbit.community.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.*
import androidx.work.WorkManager
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.*
import app.bookorbit.community.download.enqueueDownload
import app.bookorbit.community.playback.PAUSE_CONNECTION
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock

data class LibraryState(
    val session: Session? = null,
    val connections: List<Session> = emptyList(),
    val addingServer: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val query: String = "",
    val offline: Boolean = false,
    val sort: CatalogSort = CatalogSort.TITLE_ASC,
    val readFilter: CatalogReadFilter = CatalogReadFilter.ALL,
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

    private fun initialState(session: Session?) =
        LibraryState(
            session = session,
            connections = app.sessions.connections(),
            wifiOnly =
                session?.let { PlaybackToolsStorage(app, it.scope).options().wifiOnly } ?: true,
            readFilter =
                session?.let { CatalogReadFilterStorage(app, it.scope).read("audio") }
                    ?: CatalogReadFilter.ALL,
            sort =
                session?.let { CatalogSortStorage(app, it.scope).read("audio", false) }
                    ?: CatalogSort.TITLE_ASC,
        )

    private val mutable = MutableStateFlow(initialState(app.sessions.read()))
    val state = mutable.asStateFlow()
    private val refresh = MutableStateFlow(0)
    private var detailJob: Job? = null
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val catalog =
        combine(
                mutable
                    .map {
                        CatalogKey(it.session?.scope, it.query, it.offline, it.sort, it.readFilter)
                    }
                    .distinctUntilChanged(),
                refresh,
            ) { key, generation ->
                key to generation
            }
            .debounce(300)
            .flatMapLatest { (key, _) ->
                val owner = key.owner
                if (owner == null) flowOf(PagingData.empty())
                else
                    bookPager { page ->
                            withContext(Dispatchers.IO) {
                                if (app.sessions.read()?.scope != owner) throw ApiFailure(401)
                                val result =
                                    if (!key.offline)
                                        app.api.books(
                                            page,
                                            key.query,
                                            owner = owner,
                                            sort = key.sort,
                                            readFilter = key.readFilter,
                                        )
                                    else {
                                        val downloads =
                                            AudioStorage(app, owner).downloads(page, key.sort)
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

    fun login(
        server: String,
        username: String,
        password: String,
        controller: androidx.media3.session.MediaController? = null,
    ) {
        if (mutable.value.busy) return
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null) }
            try {
                pauseForConnection(controller)
                val session =
                    withContext(Dispatchers.IO) { app.api.login(server, username, password) }
                mutable.value = initialState(session)
                browse()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutable.update { it.copy(busy = false, error = friendly(error)) }
            }
        }
    }

    fun logout(controller: androidx.media3.session.MediaController?) {
        if (mutable.value.busy) return
        detailJob?.cancel()
        val session = app.sessions.read() ?: return
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null) }
            try {
                pauseForConnection(controller)
                WorkManager.getInstance(app).cancelAllWorkByTag("account-${session.scope}")
                try {
                    withContext(Dispatchers.IO) { app.api.logout() }
                } catch (_: IOException) {}
                mutable.value = initialState(app.sessions.read())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutable.update {
                    it.copy(busy = false, error = "Could not disconnect this server. Try again.")
                }
            }
        }
    }

    fun addServer(value: Boolean) {
        if (!mutable.value.busy) mutable.update { it.copy(addingServer = value, error = null) }
    }

    fun switchServer(scope: String, controller: androidx.media3.session.MediaController?) {
        if (mutable.value.busy || scope == mutable.value.session?.scope) return
        detailJob?.cancel()
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null) }
            try {
                pauseForConnection(controller)
                val session = withContext(Dispatchers.IO) { app.api.switchConnection(scope) }
                mutable.value = initialState(session)
                browse()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutable.update {
                    it.copy(busy = false, error = "Could not switch servers. Try again.")
                }
            }
        }
    }

    private suspend fun pauseForConnection(controller: androidx.media3.session.MediaController?) {
        if (mutable.value.session == null) return
        if (controller == null) {
            check(app.currentManifest.value == null) {
                "Wait for playback controls to reconnect before switching servers."
            }
            return
        }
        val paused =
            kotlinx.coroutines.withTimeoutOrNull(15_000) {
                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                    val future =
                        controller.sendCustomCommand(
                            androidx.media3.session.SessionCommand(
                                PAUSE_CONNECTION,
                                android.os.Bundle.EMPTY,
                            ),
                            android.os.Bundle.EMPTY,
                        )
                    future.addListener(
                        {
                            if (continuation.isActive) {
                                val result = runCatching {
                                    check(
                                        future.get().resultCode ==
                                            androidx.media3.session.SessionResult.RESULT_SUCCESS
                                    )
                                }
                                result.fold(
                                    { continuation.resumeWith(Result.success(Unit)) },
                                    { continuation.resumeWith(Result.failure(it)) },
                                )
                            }
                        },
                        androidx.core.content.ContextCompat.getMainExecutor(app),
                    )
                }
            }
        check(paused != null) { "Could not pause playback before switching servers." }
    }

    fun readFilter(value: CatalogReadFilter) {
        val owner = mutable.value.session?.scope ?: return
        CatalogReadFilterStorage(app, owner).save("audio", value)
        mutable.update { it.copy(readFilter = value, error = null) }
    }

    fun sort(value: CatalogSort) {
        val snapshot = mutable.value
        if (snapshot.offline && !value.local) return
        val owner = snapshot.session?.scope ?: return
        CatalogSortStorage(app, owner).save("audio", snapshot.offline, value)
        mutable.update { it.copy(sort = value, error = null) }
    }

    fun search(value: String) {
        mutable.update { it.copy(query = value, error = null) }
    }

    fun setOffline(value: Boolean) {
        mutable.update {
            it.copy(
                offline = value,
                query = "",
                error = null,
                sort =
                    it.session?.let { account ->
                        CatalogSortStorage(app, account.scope).read("audio", value)
                    } ?: CatalogSort.TITLE_ASC,
            )
        }
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

private data class CatalogKey(
    val owner: String?,
    val query: String,
    val offline: Boolean,
    val sort: CatalogSort,
    val readFilter: CatalogReadFilter,
)

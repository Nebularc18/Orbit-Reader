package app.bookorbit.community.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.*
import app.bookorbit.community.download.enqueueDownload
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock

data class LibraryState(
    val session: Session? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val books: BooksPage? = null,
    val query: String = "",
    val page: Int = 1,
    val offline: Boolean = false,
    val selected: AudioManifest? = null,
    val downloaded: Boolean = false,
    val wifiOnly: Boolean = true,
    val downloads: List<StoredDownload> = emptyList(),
    val usedBytes: Long = 0,
    val availableBytes: Long = 0,
    val selectedBytes: Long = 0,
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OrbitApplication
    private val mutable = MutableStateFlow(LibraryState(session = app.sessions.read()))
    val state = mutable.asStateFlow()
    private var listingJob: Job? = null
    private var detailJob: Job? = null

    init {
        if (mutable.value.session != null) browse()
    }

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
        listingJob?.cancel()
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
        mutable.update { it.copy(query = value, page = 1) }
        browse(debounce = true)
    }

    fun setOffline(value: Boolean) {
        mutable.update { it.copy(offline = value, query = "", page = 1, books = null) }
        browse()
    }

    fun page(delta: Int) {
        mutable.update { it.copy(page = (it.page + delta).coerceAtLeast(1)) }
        browse()
    }

    fun browse(debounce: Boolean = false) {
        listingJob?.cancel()
        listingJob =
            viewModelScope.launch {
                if (debounce) delay(350)
                val snapshot = mutable.value
                val account = snapshot.session ?: return@launch
                mutable.update { it.copy(busy = true, error = null) }
                try {
                    val result =
                        withContext(Dispatchers.IO) {
                            if (!snapshot.offline) app.api.books(snapshot.page, snapshot.query)
                            else {
                                val stored =
                                    AudioStorage(app, account.scope).downloads(snapshot.page).map {
                                        it.manifest
                                    }
                                BooksPage(
                                    stored.map {
                                        BookCard(it.book.id, it.book.title, it.book.authors)
                                    },
                                    -1,
                                    snapshot.page,
                                    30,
                                )
                            }
                        }
                    mutable.update { it.copy(books = result, busy = false) }
                    refreshDownloads()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    mutable.update { it.copy(busy = false, error = friendly(error)) }
                }
            }
    }

    fun select(bookId: Int) {
        detailJob?.cancel()
        detailJob =
            viewModelScope.launch {
                mutable.update { it.copy(busy = true, error = null) }
                try {
                    val storage = AudioStorage(app, mutable.value.session!!.scope)
                    val manifest =
                        withContext(Dispatchers.IO) {
                            if (mutable.value.offline)
                                storage.manifest(bookId) ?: throw IOException()
                            else app.api.manifest(bookId)
                        }
                    mutable.update {
                        it.copy(
                            selected = manifest,
                            downloaded = storage.downloaded(manifest),
                            selectedBytes = storage.audioBytes(bookId),
                            busy = false,
                        )
                    }
                    refreshDownloads()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    mutable.update { it.copy(busy = false, error = friendly(error)) }
                }
            }
    }

    fun closeDetail() {
        mutable.update { it.copy(selected = null) }
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
            val downloads = if (snapshot.offline) storage.downloads(snapshot.page) else emptyList()
            val used = storage.usedBytes()
            val available = storage.availableBytes()
            val complete = snapshot.selected?.let(storage::downloaded) ?: false
            val selectedBytes = snapshot.selected?.let { storage.audioBytes(it.book.id) } ?: 0
            mutable.update {
                if (
                    it.session?.scope != account.scope ||
                        it.page != snapshot.page ||
                        it.offline != snapshot.offline ||
                        it.selected != snapshot.selected
                )
                    it
                else
                    it.copy(
                        downloads = downloads,
                        usedBytes = used,
                        availableBytes = available,
                        downloaded = complete,
                        selectedBytes = selectedBytes,
                        books =
                            if (snapshot.offline)
                                BooksPage(
                                    downloads.map { entry ->
                                        BookCard(
                                            entry.manifest.book.id,
                                            entry.manifest.book.title,
                                            entry.manifest.book.authors,
                                        )
                                    },
                                    -1,
                                    snapshot.page,
                                    30,
                                )
                            else it.books,
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

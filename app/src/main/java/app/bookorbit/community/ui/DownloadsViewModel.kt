package app.bookorbit.community.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.AudioStorage
import app.bookorbit.community.data.BookCard
import app.bookorbit.community.data.EbookFile
import app.bookorbit.community.data.LocalDownloadStatus
import app.bookorbit.community.reader.EbookStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class OfflineEntry(val book: BookCard, val bytes: Long, val file: EbookFile? = null) {
    val key: String
        get() = file?.let { "ebook-${it.id}" } ?: "audio-${book.id}"
}

data class DownloadsState(
    val entries: List<OfflineEntry> = emptyList(),
    val bytes: Long = 0,
    val availableBytes: Long = 0,
    val loading: Boolean = true,
)

class DownloadsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OrbitApplication
    private val owner = app.sessions.read()?.scope
    private val mutable = MutableStateFlow(DownloadsState())
    val state = mutable.asStateFlow()
    private var load: Job? = null

    fun refresh() {
        val scope = owner ?: return
        load?.cancel()
        load =
            viewModelScope.launch {
                val next =
                    withContext(Dispatchers.IO) {
                        if (app.sessions.read()?.scope != scope) return@withContext null
                        val audio = AudioStorage(app, scope)
                        val (audiobooks, audioBytes) =
                            audio.downloadInventory { download ->
                                val book = download.manifest.book
                                OfflineEntry(
                                    BookCard(
                                        book.id,
                                        book.title,
                                        book.authors,
                                        localDownload =
                                            LocalDownloadStatus(download.bytes, download.complete),
                                    ),
                                    download.bytes,
                                )
                            }
                        val ebooks =
                            EbookStorage(app, scope).savedEntries { saved, bytes ->
                                OfflineEntry(
                                    BookCard(saved.bookId, saved.title, saved.authors),
                                    bytes,
                                    saved.file,
                                )
                            }
                        DownloadsState(
                            audiobooks + ebooks,
                            audioBytes + ebooks.sumOf { it.bytes },
                            audio.availableBytes(),
                            loading = false,
                        )
                    }
                if (next != null && app.sessions.read()?.scope == scope) mutable.value = next
            }
    }
}

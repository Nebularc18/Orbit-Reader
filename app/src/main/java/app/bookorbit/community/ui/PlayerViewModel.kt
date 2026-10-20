package app.bookorbit.community.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.AudioBookmark
import app.bookorbit.community.data.AudioManifest
import app.bookorbit.community.data.ListeningStorage
import app.bookorbit.community.data.SkipIntervals
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ListeningState(
    val bookmarks: List<AudioBookmark> = emptyList(),
    val intervals: SkipIntervals = SkipIntervals(),
    val busy: Boolean = false,
    val error: String? = null,
)

class PlayerViewModel(
    private val app: OrbitApplication,
    private val owner: String,
    private val book: AudioManifest,
) : ViewModel() {
    private val mutable = MutableStateFlow(ListeningState())
    val state = mutable.asStateFlow()

    init {
        update {}
    }

    fun addBookmark(assetIndex: Int, positionMs: Long, title: String) {
        val asset = book.assets.getOrNull(assetIndex) ?: return
        update {
            addBookmark(
                book.book.id,
                AudioBookmark(
                    assetId = asset.assetId,
                    positionMs = positionMs.coerceAtLeast(0),
                    manifestRevision = book.revision,
                    title = title,
                ),
            )
        }
    }

    fun renameBookmark(id: String, title: String) = update {
        renameBookmark(book.book.id, id, title)
    }

    fun deleteBookmark(id: String) = update { deleteBookmark(book.book.id, id) }

    fun setIntervals(value: SkipIntervals, onSaved: () -> Unit) =
        update(onSaved) { saveIntervals(value) }

    private fun update(onSaved: () -> Unit = {}, action: ListeningStorage.() -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val loaded =
                    withContext(Dispatchers.IO) {
                        if (app.sessions.read()?.scope != owner) throw IOException()
                        val storage = ListeningStorage(app, owner)
                        storage.action()
                        ListeningState(
                            storage.bookmarks(book.book.id),
                            storage.intervals(),
                            busy = false,
                        )
                    }
                mutable.value = loaded
                if (app.sessions.read()?.scope == owner) onSaved()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutable.value =
                    mutable.value.copy(
                        busy = false,
                        error =
                            if (error is IllegalArgumentException) error.message
                            else "Could not save listening preferences. Try again.",
                    )
            }
        }
    }
}

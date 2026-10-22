package app.bookorbit.community.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class BookManagementState(
    val book: EbookDetail? = null,
    val collections: List<BookCollection> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)

class BookManagementViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OrbitApplication
    private val owner = app.sessions.read()?.scope.orEmpty()
    private val mutable = MutableStateFlow(BookManagementState())
    val state = mutable.asStateFlow()

    private var lastOriginal: EbookDetail? = null

    fun bind(book: EbookDetail) {
        if (lastOriginal != book && !mutable.value.busy) {
            lastOriginal = book
            mutable.update { it.copy(book = book, error = null) }
        }
    }

    fun collections() = perform {
        mutable.update {
            it.copy(
                collections =
                    app.api.bookCollections(owner).filter { c ->
                        c.isOwner && c.mediaType != "podcasts"
                    }
            )
        }
    }

    fun status(value: String) = update { id -> app.api.setBookStatus(id, value, owner) }

    fun rating(value: Int?) = update { id -> app.api.setBookRating(id, value, owner) }

    fun membership(collection: Int, add: Boolean) = update { id ->
        app.api.collectionMembership(collection, id, add, owner)
    }

    private fun update(action: (Int) -> Unit) = perform {
        val id = mutable.value.book!!.id
        action(id)
        val book = app.api.ebookDetail(id, owner)
        BookDetailsStorage(app, owner).save(book)
        mutable.update { it.copy(book = book) }
    }

    private fun perform(action: () -> Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(app.sessions.read()?.scope == owner)
                    action()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update {
                    it.copy(
                        error =
                            (e as? ApiFailure)?.message
                                ?: "Could not save this change. Refresh the book before trying again."
                    )
                }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }
}

@Composable
fun ManagedBookInformation(app: OrbitApplication, account: Session, original: EbookDetail) {
    val model: BookManagementViewModel = viewModel(key = "manage-${account.scope}-${original.id}")
    LaunchedEffect(original) { model.bind(original) }
    val state by model.state.collectAsStateWithLifecycle()
    val book = state.book ?: original
    var dialog by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { dialog = "status" }, enabled = !state.busy) {
                Text(statusLabel(book.readStatus?.status ?: "unread"))
            }
            TextButton(
                onClick = {
                    dialog = "collections"
                    model.collections()
                },
                enabled = !state.busy,
            ) {
                Text("Collections")
            }
        }
        if (account.user.isSuperuser || "library_edit_metadata" in account.user.permissions)
            TextButton(onClick = { dialog = "rating" }, enabled = !state.busy) {
                Text("Your rating: " + (book.rating?.toString() ?: "Not rated"))
            }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (
            book.files.any {
                it.format?.lowercase() in setOf("mp3", "m4b", "m4a", "opus", "ogg", "flac")
            }
        )
            QueueBookActions(
                account,
                QueuedBook(book.id, book.title ?: "Untitled", book.authors.map { it.name }),
            )
        BookInformation(book)
    }
    if (dialog.isNotEmpty())
        AlertDialog(
            onDismissRequest = { if (!state.busy) dialog = "" },
            title = {
                Text(
                    when (dialog) {
                        "status" -> "Reading status"
                        "rating" -> "Your rating"
                        else -> "Your collections"
                    }
                )
            },
            text = {
                Column {
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        if (dialog == "status")
                            items(bookStatuses) { value ->
                                TextButton(
                                    onClick = { model.status(value) },
                                    enabled = !state.busy,
                                ) {
                                    Text(
                                        (if ((book.readStatus?.status ?: "unread") == value) "✓ "
                                        else "") + statusLabel(value)
                                    )
                                }
                            }
                        else if (dialog == "rating")
                            items(listOf(0, 1, 2, 3, 4, 5)) { value ->
                                TextButton(
                                    onClick = { model.rating(value.takeIf { it > 0 }) },
                                    enabled = !state.busy,
                                ) {
                                    Text(if (value == 0) "Clear rating" else "★".repeat(value))
                                }
                            }
                        else {
                            if (!state.busy && state.collections.isEmpty())
                                item {
                                    Text(
                                        "No editable collections. Create a collection on your server first."
                                    )
                                }
                            items(state.collections, key = { it.id }) { collection ->
                                val selected = book.collections.any { it.id == collection.id }
                                Row {
                                    Checkbox(
                                        selected,
                                        { model.membership(collection.id, it) },
                                        enabled = !state.busy,
                                    )
                                    TextButton(
                                        onClick = { model.membership(collection.id, !selected) },
                                        enabled = !state.busy,
                                    ) {
                                        Text(collection.name)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { dialog = "" }, enabled = !state.busy) { Text("Done") }
            },
        )
}

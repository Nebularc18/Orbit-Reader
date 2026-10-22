package app.bookorbit.community.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.*
import app.bookorbit.community.download.enqueueDownload
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PlaybackToolsState(
    val options: PlaybackOptions = PlaybackOptions(),
    val queue: List<QueuedBook> = emptyList(),
    val used: Long = 0,
    val error: String? = null,
)

class PlaybackToolsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OrbitApplication
    val owner = app.sessions.read()?.scope.orEmpty()
    private val mutable = MutableStateFlow(PlaybackToolsState())
    private val actionLock = Mutex()
    val state = mutable.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = action { storage ->
        mutable.value =
            PlaybackToolsState(storage.options(), storage.queue(), app.offlineBudget.used(owner))
    }

    fun options(value: PlaybackOptions) = action { storage ->
        storage.options(value)
        mutable.update { it.copy(options = value, error = null) }
    }

    fun add(book: QueuedBook, next: Boolean) = action { storage ->
        storage.add(book, next)
        mutable.update { it.copy(queue = storage.queue(), error = null) }
        val session = app.sessions.read()!!
        if (storage.options().prefetchNext && session.canDownload)
            storage.queue().firstOrNull()?.let { enqueueDownload(app, session, it.id, true) }
    }

    fun remove(id: Int) = action { storage ->
        storage.remove(id)
        mutable.update { it.copy(queue = storage.queue()) }
    }

    fun move(id: Int, delta: Int) = action { storage ->
        storage.move(id, delta)
        mutable.update { it.copy(queue = storage.queue()) }
    }

    private fun action(block: (PlaybackToolsStorage) -> Unit) {
        viewModelScope.launch {
            try {
                actionLock.withLock {
                    withContext(Dispatchers.IO) {
                        check(app.sessions.read()?.scope == owner)
                        block(PlaybackToolsStorage(app, owner))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update {
                    it.copy(error = e.message ?: "Could not update playback settings.")
                }
            }
        }
    }
}

@Composable
fun QueueBookActions(account: Session, book: QueuedBook) {
    val model: PlaybackToolsViewModel = viewModel(key = "tools-${account.scope}")
    var message by remember { mutableStateOf("") }
    val state by model.state.collectAsStateWithLifecycle()
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    model.add(book, true)
                    message = "Added to play next"
                }
            ) {
                Text("Play next")
            }
            TextButton(
                onClick = {
                    model.add(book, false)
                    message = "Added to queue"
                }
            ) {
                Text("Add to queue")
            }
        }
        if (message.isNotEmpty())
            Text(
                state.error ?: if (state.queue.any { it.id == book.id }) message else "Saving…",
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (state.error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
            )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaybackToolsDialog(account: Session, onClose: () -> Unit, onPlay: (Int) -> Unit) {
    val model: PlaybackToolsViewModel = viewModel(key = "tools-${account.scope}")
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { model.refresh() }
    var tab by remember { mutableIntStateOf(0) }
    val options = state.options
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Playback & downloads") },
        text = {
            Column(Modifier.heightIn(max = 520.dp)) {
                SegmentPicker(listOf("Queue", "Playback", "Offline"), tab, { tab = it })
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (tab == 0) {
                        OptionSwitch("Play queue automatically", options.autoplayQueue) {
                            model.options(options.copy(autoplayQueue = it))
                        }
                        if (state.queue.isEmpty())
                            Text("Your queue is empty. Add books from their information screen.")
                        state.queue.forEachIndexed { index, book ->
                            Column {
                                Text(book.title, style = MaterialTheme.typography.titleSmall)
                                FlowRow {
                                    TextButton(
                                        onClick = {
                                            onPlay(book.id)
                                            onClose()
                                        }
                                    ) {
                                        Text("Play")
                                    }
                                    TextButton(
                                        onClick = { model.move(book.id, -1) },
                                        enabled = index > 0,
                                    ) {
                                        Text("Up")
                                    }
                                    TextButton(
                                        onClick = { model.move(book.id, 1) },
                                        enabled = index < state.queue.lastIndex,
                                    ) {
                                        Text("Down")
                                    }
                                    TextButton(onClick = { model.remove(book.id) }) {
                                        Text("Remove")
                                    }
                                }
                            }
                        }
                    } else if (tab == 1) {
                        OptionSwitch("Rewind after a long pause", options.smartResume) {
                            model.options(options.copy(smartResume = it))
                        }
                        Text(
                            "Rewind after pausing for at least a minute. Seeking manually keeps your chosen position.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row {
                            listOf(5, 10, 15, 30).forEach { seconds ->
                                FilterChip(
                                    options.rewindSeconds == seconds,
                                    { model.options(options.copy(rewindSeconds = seconds)) },
                                    label = { Text("${seconds}s") },
                                )
                            }
                        }
                        OptionSwitch("Continue the next book in a series", options.continueSeries) {
                            model.options(options.copy(continueSeries = it))
                        }
                        Text(
                            "Your enabled queue takes priority. Playback stops if progress needs conflict review.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(
                            "${android.text.format.Formatter.formatFileSize(androidx.compose.ui.platform.LocalContext.current,state.used)} downloaded on this server",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text("Download storage limit", style = MaterialTheme.typography.titleSmall)
                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(5) { index ->
                                val size = listOf(0, 1, 2, 5, 10)[index]
                                FilterChip(
                                    options.storageGiB == size,
                                    { model.options(options.copy(storageGiB = size)) },
                                    label = { Text(if (size == 0) "No limit" else "$size GB") },
                                )
                            }
                        }
                        Text(
                            "Lowering the limit keeps existing books and blocks new downloads until there is room.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OptionSwitch("Download on Wi-Fi only", options.wifiOnly) {
                            model.options(options.copy(wifiOnly = it))
                        }
                        OptionSwitch(
                            "Download the next queued or series book on Wi-Fi",
                            options.prefetchNext,
                        ) {
                            model.options(options.copy(prefetchNext = it))
                        }
                        OptionSwitch(
                            "Remove finished audiobook downloads",
                            options.removeFinishedAudio,
                        ) {
                            model.options(options.copy(removeFinishedAudio = it))
                        }
                        Text(
                            "Only after playback reaches the end. Bookmarks and listening position are kept.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
}

@Composable
private fun OptionSwitch(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, null)
    }
}

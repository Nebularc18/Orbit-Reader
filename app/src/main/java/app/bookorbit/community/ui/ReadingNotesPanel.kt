package app.bookorbit.community.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.reader.ReadingNote
import app.bookorbit.community.reader.ReadingNotes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ReadingNotesViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OrbitApplication
    private val mutable = MutableStateFlow<List<ReadingNote>>(emptyList())
    val notes = mutable.asStateFlow()
    val error = MutableStateFlow<String?>(null)
    private val persistence = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: Job? = null
    private var owner = ""
    private var fileId = 0

    fun load(scope: String, file: Int) {
        owner = scope
        fileId = file
        update {}
    }

    fun save(value: ReadingNote) = update { save(value) }

    fun delete(id: String) = update { delete(id) }

    private fun update(action: ReadingNotes.() -> Unit) {
        val previous = pending
        val account = owner
        val bookFile = fileId
        pending =
            persistence.launch {
                previous?.join()
                try {
                    mutable.value =
                        withContext(Dispatchers.IO) {
                            check(app.sessions.read()?.scope == account)
                            ReadingNotes(app, account, bookFile).apply(action).all()
                        }
                    error.value = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error.value = e.message ?: "Could not save notes."
                }
            }
    }

    override fun onCleared() {
        runBlocking { pending?.join() }
        persistence.cancel()
        super.onCleared()
    }
}

@Composable
fun ReadingNotesPanel(
    notes: List<ReadingNote>,
    revision: String,
    error: String?,
    onDismiss: () -> Unit,
    onGo: (ReadingNote) -> Unit,
    onSave: (ReadingNote) -> Unit,
    onDelete: (String) -> Unit,
    owner: String? = null,
    fileId: Int = 0,
    onReload: () -> Unit = {},
) {
    var editing by remember { mutableStateOf<ReadingNote?>(null) }
    var server by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf<ReadingNote?>(null) }
    if (server && owner != null)
        ServerAnnotationsDialog(owner, fileId, { server = false }, onReload)
    sharing?.let { item ->
        if (owner != null) ShareAnnotationDialog(owner, fileId, item, { sharing = null }, onReload)
    }
    if (editing == null)
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Bookmarks and notes") },
            text = {
                Column {
                    Text("Saved on this device for this account.")
                    NotesExportButton(owner, notes.isNotEmpty()) { markdown ->
                        exportReadingNotes("Book notes", notes, markdown)
                    }
                    if (owner != null)
                        TextButton(onClick = { server = true }) { Text("Server annotations") }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (notes.isEmpty()) Text("No saved passages yet.")
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(notes, key = { it.id }) { item ->
                            Column {
                                TextButton(
                                    onClick = {
                                        onGo(item)
                                        onDismiss()
                                    },
                                    enabled = item.revision == revision,
                                ) {
                                    Text(item.title)
                                }
                                if (item.revision != revision)
                                    Text("Book changed. Location unavailable.")
                                if (item.quote.isNotEmpty())
                                    Text(
                                        item.quote.take(500),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                if (item.note.isNotEmpty()) Text(item.note.take(500))
                                Row {
                                    TextButton(onClick = { editing = item }) { Text("Edit note") }
                                    TextButton(onClick = { onDelete(item.id) }) {
                                        Text("Delete note")
                                    }
                                }
                                if (
                                    owner != null &&
                                        item.serverId == null &&
                                        item.revision == revision &&
                                        (item.cfi != null || item.pdf != null)
                                )
                                    TextButton(onClick = { sharing = item }) {
                                        Text("Share annotation")
                                    }
                                if (item.serverId != null)
                                    Text(
                                        "Shared annotation. Local edits stay on this device.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
    editing?.let { value ->
        ReadingNoteEditor(value, { editing = null }) {
            onSave(it)
            editing = null
        }
    }
}

@Composable
fun ReadingNoteEditor(initial: ReadingNote, onDismiss: () -> Unit, onSave: (ReadingNote) -> Unit) {
    var title by remember(initial.id) { mutableStateOf(initial.title) }
    var note by remember(initial.id) { mutableStateOf(initial.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.highlight) "Highlight and note" else "Bookmark and note") },
        text = {
            Column {
                OutlinedTextField(
                    title,
                    { title = it.take(120) },
                    label = { Text("Name") },
                    singleLine = true,
                )
                if (initial.quote.isNotEmpty())
                    Text(initial.quote.take(300), Modifier.padding(vertical = 8.dp))
                OutlinedTextField(
                    note,
                    { note = it.take(10000) },
                    label = { Text("Note") },
                    modifier = Modifier.heightIn(max = 200.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(initial.copy(title = title.trim(), note = note)) },
                enabled = title.isNotBlank(),
            ) {
                Text("Save note")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.*
import app.bookorbit.community.reader.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString

class ServerAnnotationsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OrbitApplication
    val rows = MutableStateFlow<AnnotationPage?>(null)
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    fun run(owner: String, fileId: Int, action: suspend (SavedEbook) -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(app.sessions.read()?.scope == owner)
                    val storage = EbookStorage(app, owner)
                    val book = storage.book(fileId) ?: error("Open the book again.")
                    val latest =
                        app.api.ebookDetail(book.bookId, owner).files.firstOrNull {
                            it.id == fileId
                        }
                    require(latest?.revision == book.file.revision) {
                        "The server file changed. Download it again before sharing annotations."
                    }
                    action(book)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value =
                    e.message ?: "Could not contact the server. Refresh before retrying a write."
            } finally {
                busy.value = false
            }
        }
    }

    fun refresh(owner: String, fileId: Int, page: Int) =
        run(owner, fileId) {
            rows.value = app.api.annotations(it.bookId, fileId, owner, page)
            message.value = null
        }

    fun publish(owner: String, fileId: Int, note: ReadingNote, done: (Int) -> Unit) =
        run(owner, fileId) { book ->
            require(note.revision == book.file.revision)
            val created =
                app.api.createAnnotation(
                    book.bookId,
                    owner,
                    AnnotationCreate(
                        fileId,
                        note.quote.ifBlank { note.title },
                        note.cfi,
                        note.pdf,
                        note = note.note.ifBlank { null },
                        chapterTitle = note.title,
                    ),
                )
            ReadingNotes(app, owner, fileId).save(note.copy(serverId = created.id))
            message.value =
                "Annotation shared. Server edits and local copies are managed separately."
            withContext(Dispatchers.Main) { done(created.id) }
        }

    fun importCopy(owner: String, fileId: Int, row: BookAnnotation, done: () -> Unit) =
        run(owner, fileId) { book ->
            require(row.jumpFileId == fileId) { "This annotation belongs to a different edition." }
            val locator =
                if (row.cfi != null)
                    EpubCfi(EbookStorage(app, owner).content(book.file))
                        .toLocator(row.cfi)
                        ?.toJSON()
                        ?.toString()
                else row.pdf?.let { wireJson.encodeToString(PageLocation(it.page)) }
            require(locator != null) {
                "This annotation's location could not be resolved in this file."
            }
            ReadingNotes(app, owner, fileId)
                .save(
                    ReadingNote(
                        id = "server-${row.id}",
                        revision = book.file.revision,
                        locator = locator,
                        title = "Shared passage ${row.id}",
                        quote = row.text.take(10000),
                        note = row.note.orEmpty().take(10000),
                        highlight = row.style == "highlight",
                        cfi = row.cfi,
                        pdf = row.pdf,
                        serverId = row.id,
                    )
                )
            message.value = "Saved a local copy."
            withContext(Dispatchers.Main) { done() }
        }

    fun edit(owner: String, fileId: Int, row: BookAnnotation, note: String) =
        run(owner, fileId) { book ->
            app.api.editAnnotation(book.bookId, row.id, owner, note.ifBlank { null })
            rows.value = app.api.annotations(book.bookId, fileId, owner, rows.value?.page ?: 1)
            message.value = "Server note updated. Refresh saved local copies separately."
        }

    fun delete(owner: String, fileId: Int, row: BookAnnotation) =
        run(owner, fileId) { book ->
            app.api.deleteAnnotation(book.bookId, row.id, owner)
            rows.value = app.api.annotations(book.bookId, fileId, owner, rows.value?.page ?: 1)
            message.value = "Server annotation deleted. Local copies are retained."
        }
}

@Composable
fun ServerAnnotationsDialog(
    owner: String,
    fileId: Int,
    onDismiss: () -> Unit,
    onImported: () -> Unit,
) {
    val model: ServerAnnotationsViewModel = viewModel(key = "server-annotations-$owner-$fileId")
    val page by model.rows.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<BookAnnotation?>(null) }
    var deleting by remember { mutableStateOf<BookAnnotation?>(null) }
    LaunchedEffect(Unit) { model.refresh(owner, fileId, 1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Server annotations") },
        text = {
            Column {
                Text(
                    "Save a local copy to read it offline. Server edits replace the note shown here, including changes made on another device."
                )
                if (busy) LinearProgressIndicator()
                message?.let { Text(it) }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(page?.items.orEmpty(), key = { it.id }) { row ->
                        Column {
                            Text(row.text.take(500))
                            row.note?.let { Text(it.take(500)) }
                            Row {
                                TextButton(
                                    onClick = { model.importCopy(owner, fileId, row, onImported) },
                                    enabled = !busy,
                                ) {
                                    Text("Save on device")
                                }
                                TextButton(onClick = { editing = row }, enabled = !busy) {
                                    Text("Edit server note")
                                }
                            }
                            TextButton(onClick = { deleting = row }, enabled = !busy) {
                                Text("Delete from server")
                            }
                            HorizontalDivider()
                        }
                    }
                }
                Row {
                    TextButton(
                        onClick = { model.refresh(owner, fileId, (page?.page ?: 1) - 1) },
                        enabled = !busy && (page?.page ?: 1) > 1,
                    ) {
                        Text("Previous")
                    }
                    TextButton(
                        onClick = { model.refresh(owner, fileId, (page?.page ?: 1) + 1) },
                        enabled = !busy && (page?.page ?: 1) * 25 < (page?.total ?: 0),
                    ) {
                        Text("Next")
                    }
                    TextButton(
                        onClick = { model.refresh(owner, fileId, page?.page ?: 1) },
                        enabled = !busy,
                    ) {
                        Text("Refresh")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
    editing?.let { row ->
        var text by remember(row.id) { mutableStateOf(row.note.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit server note") },
            text = { OutlinedTextField(text, { text = it.take(10000) }, label = { Text("Note") }) },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.edit(owner, fileId, row, text)
                        editing = null
                    }
                ) {
                    Text("Update server note")
                }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete server annotation?") },
            text = {
                Text("This removes the annotation from BookOrbit and other connected clients.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.delete(owner, fileId, row)
                        deleting = null
                    }
                ) {
                    Text("Delete from server")
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ShareAnnotationDialog(
    owner: String,
    fileId: Int,
    note: ReadingNote,
    onDismiss: () -> Unit,
    onShared: () -> Unit,
) {
    val model: ServerAnnotationsViewModel =
        viewModel(key = "share-annotation-$owner-$fileId-${note.id}")
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    var published by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share annotation") },
        text = {
            Column {
                Text(note.quote.ifBlank { note.title }.take(500))
                Text(note.note.take(500))
                Text(
                    "This creates an annotation in BookOrbit. If a write fails, check Server annotations before retrying to avoid duplicates."
                )
                message?.let { Text(it) }
                if (busy) LinearProgressIndicator()
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    model.publish(owner, fileId, note) {
                        published = true
                        onShared()
                    }
                },
                enabled = !busy && !published,
            ) {
                Text("Publish annotation")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

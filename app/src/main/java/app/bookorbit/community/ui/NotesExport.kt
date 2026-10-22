package app.bookorbit.community.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.AudioBookmark
import app.bookorbit.community.data.AudioManifest
import app.bookorbit.community.reader.ReadingNote
import kotlinx.coroutines.*

private fun exportText(value: String, markdown: Boolean): String =
    if (!markdown) value
    else
        buildString {
            value.forEach { char ->
                when (char) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '\\',
                    '`',
                    '*',
                    '_',
                    '{',
                    '}',
                    '[',
                    ']',
                    '(',
                    ')',
                    '#',
                    '+',
                    '!',
                    '|' -> {
                        append('\\')
                        append(char)
                    }
                    else -> append(char)
                }
            }
        }

fun exportReadingNotes(title: String, notes: List<ReadingNote>, markdown: Boolean): String =
    buildString {
        append(if (markdown) "# " else "")
        append(exportText(title, markdown))
        append("\n\n")
        notes.forEach { item ->
            append(if (markdown) "## " else "")
            append(exportText(item.title, markdown))
            append("\n")
            if (item.quote.isNotBlank()) {
                if (markdown)
                    item.quote.lines().forEach {
                        append("> ")
                        append(exportText(it, true))
                        append('\n')
                    }
                else {
                    append(item.quote)
                    append('\n')
                }
            }
            if (item.note.isNotBlank()) {
                append(exportText(item.note, markdown))
                append('\n')
            }
            append('\n')
        }
    }

fun exportAudioBookmarks(
    book: AudioManifest,
    bookmarks: List<AudioBookmark>,
    markdown: Boolean,
): String = buildString {
    append(if (markdown) "# " else "")
    append(exportText(book.book.title, markdown))
    append("\n\n")
    bookmarks.forEach { item ->
        append("- ")
        append(exportText(item.title, markdown))
        append(" · ")
        val index = book.assets.indexOfFirst { it.assetId == item.assetId }
        append(
            if (index >= 0) "Part ${index+1} · ${duration(item.positionMs)}"
            else "Location no longer available"
        )
        append('\n')
    }
}

class NotesExportState : ViewModel() {
    var pending by mutableStateOf("")
}

@Composable
fun NotesExportButton(owner: String?, enabled: Boolean, content: (Boolean) -> String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var format by remember { mutableStateOf<Boolean?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val exportState: NotesExportState = viewModel(key = "notes-export-$owner")
    val save =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
            uri ->
            if (uri != null)
                scope.launch {
                    val text = exportState.pending
                    message =
                        try {
                            withContext(Dispatchers.IO) {
                                check(text.isNotBlank())
                                check(
                                    owner == null ||
                                        (context.applicationContext as OrbitApplication)
                                            .sessions
                                            .read()
                                            ?.scope == owner
                                )
                                context.contentResolver
                                    .openOutputStream(uri, "wt")!!
                                    .bufferedWriter()
                                    .use { it.write(text) }
                            }
                            "Export saved"
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            "Could not save this export. Choose a writable destination and try again."
                        }
                    exportState.pending = ""
                }
            else exportState.pending = ""
        }
    TextButton(onClick = { format = true }, enabled = enabled) { Text("Export notes") }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (format != null)
        AlertDialog(
            onDismissRequest = { format = null },
            title = { Text("Export notes") },
            text = { Text("Save a copy using the Android file picker.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        exportState.pending = content(true)
                        format = null
                        save.launch("Orbit-notes.md")
                    }
                ) {
                    Text("Markdown")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        exportState.pending = content(false)
                        format = null
                        save.launch("Orbit-notes.txt")
                    }
                ) {
                    Text("Plain text")
                }
            },
        )
}

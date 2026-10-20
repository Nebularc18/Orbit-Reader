package app.bookorbit.community.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.bookorbit.community.data.AudioBookmark
import app.bookorbit.community.data.AudioManifest
import app.bookorbit.community.data.ListeningStorage
import app.bookorbit.community.data.SkipIntervals
import app.bookorbit.community.data.skipSeconds

@Composable
fun SkipIntervalsDialog(
    initial: SkipIntervals,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (SkipIntervals) -> Unit,
) {
    var back by remember(initial) { mutableIntStateOf(initial.backSeconds) }
    var forward by remember(initial) { mutableIntStateOf(initial.forwardSeconds) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Skip intervals") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Applies to all audiobooks for this account, including media controls.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Column {
                        Text("Back")
                        skipSeconds.forEach { seconds ->
                            Row(
                                Modifier.heightIn(min = 48.dp)
                                    .selectable(
                                        selected = back == seconds,
                                        enabled = !busy,
                                        role = Role.RadioButton,
                                        onClick = { back = seconds },
                                    )
                                    .semantics { contentDescription = "Back $seconds seconds" },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = back == seconds,
                                    onClick = null,
                                    enabled = !busy,
                                )
                                Text("$seconds seconds")
                            }
                        }
                    }
                    Column {
                        Text("Forward")
                        skipSeconds.forEach { seconds ->
                            Row(
                                Modifier.heightIn(min = 48.dp)
                                    .selectable(
                                        selected = forward == seconds,
                                        enabled = !busy,
                                        role = Role.RadioButton,
                                        onClick = { forward = seconds },
                                    )
                                    .semantics { contentDescription = "Forward $seconds seconds" },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = forward == seconds,
                                    onClick = null,
                                    enabled = !busy,
                                )
                                Text("$seconds seconds")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(SkipIntervals(back, forward)) }, enabled = !busy) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun BookmarksDialog(
    book: AudioManifest,
    bookmarks: List<AudioBookmark>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onSeek: (AudioBookmark) -> Unit,
    onRename: (AudioBookmark) -> Unit,
    onDelete: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bookmarks") },
        text = {
            Column {
                Text("Saved on this device for this account.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(
                    onClick = onAdd,
                    enabled = !busy && bookmarks.size < ListeningStorage.MAX_BOOKMARKS,
                ) {
                    Text("Add current position")
                }
                if (bookmarks.isEmpty())
                    Text(if (busy) "Loading bookmarks…" else "No bookmarks yet.")
                if (bookmarks.size >= ListeningStorage.MAX_BOOKMARKS)
                    Text("Bookmark limit reached. Remove one to add another.")
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(bookmarks, key = { it.id }) { bookmark ->
                        val target = bookmark.target(book)
                        Column {
                            TextButton(onClick = { onSeek(bookmark) }, enabled = target != null) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(bookmark.title)
                                    Text(
                                        if (target != null)
                                            "Part ${target + 1} · ${duration(bookmark.positionMs)}"
                                        else "Audio changed. Add a new bookmark.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                IconButton(onClick = { onRename(bookmark) }, enabled = !busy) {
                                    Icon(Icons.Rounded.Edit, "Rename ${bookmark.title}")
                                }
                                IconButton(onClick = { onDelete(bookmark.id) }, enabled = !busy) {
                                    Icon(Icons.Rounded.Delete, "Delete ${bookmark.title}")
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun BookmarkNameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var title by
        remember(initial) { mutableStateOf(initial.take(ListeningStorage.MAX_TITLE_LENGTH)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bookmark name") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(ListeningStorage.MAX_TITLE_LENGTH) },
                label = { Text("Name") },
                singleLine = true,
                supportingText = { Text("${title.length}/${ListeningStorage.MAX_TITLE_LENGTH}") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(title.trim()) }, enabled = title.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

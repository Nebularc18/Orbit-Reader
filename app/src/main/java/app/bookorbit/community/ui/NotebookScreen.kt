package app.bookorbit.community.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.bookorbit.community.data.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotebookScreen(
    account: Session,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onAudio: (Int) -> Unit,
) {
    val model: NotebookViewModel = viewModel(key = "notebook-${account.scope}")
    val state by model.state.collectAsStateWithLifecycle()
    var selected by remember(account.scope) { mutableStateOf<NotebookEntry?>(null) }
    var editing by remember(account.scope) { mutableStateOf<NotebookEntry?>(null) }
    var deleting by remember(account.scope) { mutableStateOf<NotebookEntry?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(state.query, state.filter, state.page) { listState.scrollToItem(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.refresh()
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) model.refresh()
        onDispose { lifecycle.removeObserver(observer) }
    }
    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            "Notebook",
            "Saved on this device for this account",
            back = onBack,
            refresh = { model.refresh() },
        )
        OutlinedTextField(
            state.query,
            model::search,
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            label = { Text("Search Notebook") },
            placeholder = { Text("Books, authors, passages, notes") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
        )
        FlowRow(
            Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NotebookFilter.entries.forEach { filter ->
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { model.filter(filter) },
                    label = { Text(filter.label) },
                )
            }
        }
        state.error?.let { message ->
            Box(Modifier.padding(horizontal = 20.dp)) {
                LibraryErrorState(message) { model.refresh() }
            }
        }
        if (state.result.unreadableFiles > 0)
            Text(
                "Some saved files could not be read. Other entries are shown.",
                Modifier.padding(horizontal = 20.dp),
                color = MaterialTheme.colorScheme.error,
            )
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(
            Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.loading && state.error == null && state.result.entries.isEmpty())
                item {
                    LibraryEmptyState(
                        if (
                            state.query.isBlank() &&
                                state.filter == NotebookFilter.ALL &&
                                state.page == 1
                        )
                            "Your Notebook is empty"
                        else "No matching entries",
                        if (state.page > 1) "Go back a page or change your search."
                        else
                            "Save highlights, notes, or bookmarks while reading or listening. Search includes book titles and authors.",
                    )
                }
            items(state.result.entries, key = { it.key }) { entry ->
                OutlinedCard(Modifier.fillMaxWidth().clickable { selected = entry }) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            entry.kind,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(entry.bookTitle, style = MaterialTheme.typography.titleMedium)
                        if (entry.authors.isNotEmpty())
                            Text(
                                entry.authors.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        Text(entry.title, style = MaterialTheme.typography.titleSmall)
                        entry.reading
                            ?.quote
                            ?.takeIf { it.isNotBlank() }
                            ?.let { Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                        entry.reading
                            ?.note
                            ?.takeIf { it.isNotBlank() }
                            ?.let {
                                Text(
                                    it,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        Text(
                            entry.location,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = { model.page(-1) }, enabled = !state.loading && state.page > 1) {
                Text("Previous")
            }
            Text(
                "Page ${state.page}",
                Modifier.padding(12.dp),
                style = MaterialTheme.typography.labelLarge,
            )
            TextButton(
                onClick = { model.page(1) },
                enabled = !state.loading && state.result.hasNext,
            ) {
                Text("Next")
            }
        }
    }
    selected?.let { entry ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(entry.title) },
            text = {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(entry.bookTitle, style = MaterialTheme.typography.titleMedium)
                            Text(
                                entry.authors.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            entry.reading?.quote?.takeIf { it.isNotBlank() }?.let { Text(it) }
                            entry.reading?.note?.takeIf { it.isNotBlank() }?.let { Text(it) }
                        }
                    }
                    Text(entry.location, style = MaterialTheme.typography.bodySmall)
                    if (entry.reading?.serverId != null)
                        Text(
                            "Shared annotation. Changes here stay on this device.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    entry.reading?.let { note ->
                        NotesExportButton(account.scope, true) { markdown ->
                            exportReadingNotes(entry.bookTitle, listOf(note), markdown)
                        }
                    }
                    if (entry.audio != null && entry.bookId != null)
                        TextButton(
                            onClick = {
                                selected = null
                                onAudio(entry.bookId)
                            }
                        ) {
                            Text("Open audiobook")
                        }
                    if (entry.reading != null)
                        TextButton(
                            enabled = entry.canRead,
                            onClick = {
                                selected = null
                                model.open(entry)
                            },
                        ) {
                            Text("Go to passage")
                        }
                    TextButton(
                        onClick = {
                            selected = null
                            editing = entry
                        }
                    ) {
                        Text(if (entry.audio != null) "Rename bookmark" else "Edit note")
                    }
                    TextButton(
                        onClick = {
                            selected = null
                            deleting = entry
                        }
                    ) {
                        Text("Delete from device")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } },
        )
    }
    editing?.let { entry ->
        if (entry.reading != null)
            ReadingNoteEditor(entry.reading, { editing = null }) {
                model.save(entry, it.title, it.note)
                editing = null
            }
        else
            BookmarkNameDialog(entry.title, { editing = null }) {
                model.save(entry, it)
                editing = null
            }
    }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this saved entry?") },
            text = {
                Text("This removes the entry from this device. Shared server annotations are kept.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.delete(entry)
                        deleting = null
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

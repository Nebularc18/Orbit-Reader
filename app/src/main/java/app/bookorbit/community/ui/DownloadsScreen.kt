package app.bookorbit.community.ui

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.CatalogSort
import app.bookorbit.community.data.CatalogSortStorage
import app.bookorbit.community.data.Session
import app.bookorbit.community.data.localBookOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DownloadsScreen(
    app: OrbitApplication,
    account: Session,
    audioWork: List<WorkInfo>,
    message: String?,
    onPlay: (Int) -> Unit,
    onAudioDetails: (Int) -> Unit,
    refreshRequest: Pair<Int, Int>,
    onSignIn: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val model: DownloadsViewModel = viewModel(key = "downloads-${account.scope}")
    val state by model.state.collectAsStateWithLifecycle()
    val ebookWork by
        remember(account.scope) {
                WorkManager.getInstance(app).getWorkInfosByTagFlow("ebooks-${account.scope}")
            }
            .collectAsStateWithLifecycle(initialValue = emptyList())
    LaunchedEffect(
        audioWork.map { it.id to it.state },
        ebookWork.map { it.id to it.state },
        refreshRequest,
    ) {
        model.refresh()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.refresh()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var query by rememberSaveable(account.scope) { mutableStateOf("") }
    var filter by rememberSaveable(account.scope) { mutableIntStateOf(0) }
    val sorts = remember(account.scope) { CatalogSortStorage(app, account.scope) }
    var sort by rememberSaveable(account.scope) { mutableStateOf(sorts.read("audio", true)) }
    var sortMenu by remember { mutableStateOf(false) }
    val filteredEntries by
        produceState<List<OfflineEntry>?>(null, state.loading, state.entries, query, filter, sort) {
            if (state.loading) {
                value = null
                return@produceState
            }
            value =
                withContext(Dispatchers.Default) {
                    val term = query.trim()
                    state.entries
                        .filter { entry ->
                            (filter == 0 || (entry.file == null) == (filter == 1)) &&
                                (entry.book.title.orEmpty().contains(term, ignoreCase = true) ||
                                    entry.book.authors.any { it.contains(term, ignoreCase = true) })
                        }
                        .sortedWith(
                            compareBy(localBookOrder(sort), OfflineEntry::book).thenBy { it.key }
                        )
                }
        }
    val entries = filteredEntries.orEmpty()
    val loading = state.loading || filteredEntries == null
    val jobs =
        remember(audioWork) {
            audioWork
                .filterNot { it.state == WorkInfo.State.CANCELLED }
                .groupBy { job -> job.tags.firstOrNull { it.startsWith("book-") } }
        }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(
                    query,
                    { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search downloads") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    colors =
                        TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                )
                if (!state.loading) {
                    StorageSummary(state.bytes, state.availableBytes)
                }
                SegmentPicker(listOf("All", "Audiobooks", "Ebooks"), filter, { filter = it })
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (!loading) {
                        Text(
                            when (entries.size) {
                                1 -> "1 download"
                                else -> "${entries.size} downloads"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Box {
                        TextButton(onClick = { sortMenu = true }) {
                            Text(sort.label)
                            Icon(Icons.Rounded.ExpandMore, null, Modifier.size(17.dp))
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            CatalogSort.entries
                                .filter { it.local }
                                .forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        onClick = {
                                            sort = option
                                            sorts.save("audio", true, option)
                                            sortMenu = false
                                        },
                                    )
                                }
                        }
                    }
                }
                if (onSignIn != null) OfflineSignIn(onSignIn)
                if (message != null) Text(message, color = MaterialTheme.colorScheme.error)
            }
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        else if (entries.isEmpty())
            item {
                Text(
                    if (state.entries.isEmpty())
                        "Download an audiobook or open an ebook to keep it here."
                    else "No matching downloads.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        items(entries, key = { it.key }) { entry ->
            val file = entry.file
            val complete = file != null || entry.book.localDownload?.complete == true
            val job = jobs["book-${entry.book.id}"]?.firstOrNull { !it.state.isFinished }
            val label =
                when {
                    file != null -> file.format?.uppercase() ?: "Ebook"
                    complete -> "Audiobook"
                    job?.state == WorkInfo.State.RUNNING -> "Downloading"
                    job != null -> "Waiting to download"
                    else -> "Incomplete audiobook"
                }
            val bytes =
                if (file == null)
                    maxOf(entry.bytes, job?.progress?.getLong("downloadedBytes", 0) ?: 0)
                else entry.bytes
            DownloadRow(
                app,
                account,
                entry,
                complete,
                label,
                Formatter.formatShortFileSize(app, bytes),
                onOpen = {
                    if (file != null) openSavedEbook(app, account.scope, file)
                    else if (complete) onPlay(entry.book.id) else onAudioDetails(entry.book.id)
                },
                onDetails =
                    if (file == null && complete) ({ onAudioDetails(entry.book.id) }) else null,
            )
        }
    }
}

@Composable
private fun StorageSummary(usedBytes: Long, freeBytes: Long) {
    val colors = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    val used = Formatter.formatShortFileSize(context, usedBytes)
    val free = Formatter.formatShortFileSize(context, freeBytes)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainer,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    used,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Saved on this device",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Text(
                "$free free on this device",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DownloadRow(
    app: OrbitApplication,
    account: Session,
    entry: OfflineEntry,
    complete: Boolean,
    format: String,
    size: String,
    onOpen: () -> Unit,
    onDetails: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val ebook = entry.file != null
    Surface(shape = MaterialTheme.shapes.large, color = colors.surfaceContainer) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f)
                    .clickable(
                        role = Role.Button,
                        onClickLabel =
                            when {
                                ebook -> "Open ebook"
                                complete -> "Play audiobook"
                                else -> "View download"
                            },
                        onClick = onOpen,
                    )
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BookCover(
                    app,
                    account,
                    entry.book.id,
                    entry.book.coverVersion,
                    Modifier.size(54.dp, if (ebook) 70.dp else 54.dp),
                    ebook,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        entry.book.title ?: "Untitled",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (entry.book.authors.isNotEmpty())
                        Text(
                            entry.book.authors.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    Text(
                        "$format · $size",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Icon(
                    when {
                        ebook -> Icons.AutoMirrored.Rounded.MenuBook
                        complete -> Icons.Rounded.PlayArrow
                        else -> Icons.Rounded.Download
                    },
                    null,
                    Modifier.size(20.dp),
                    tint = colors.primary,
                )
            }
            if (onDetails != null) {
                IconButton(onClick = onDetails) {
                    Icon(Icons.Rounded.MoreVert, "Audiobook details")
                }
            }
        }
    }
}

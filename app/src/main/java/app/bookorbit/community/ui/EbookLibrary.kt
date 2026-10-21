package app.bookorbit.community.ui

import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.Session
import app.bookorbit.community.reader.EpubReaderActivity
import app.bookorbit.community.reader.PageReaderActivity

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EbookLibrary(
    app: OrbitApplication,
    account: Session,
    modifier: Modifier = Modifier,
    openBookId: Int? = null,
    onOpenHandled: () -> Unit = {},
) {
    val model: EbookLibraryViewModel = viewModel(key = "ebooks-${account.scope}")
    val state by model.state.collectAsStateWithLifecycle()
    val catalog = model.catalog.collectAsLazyPagingItems()
    val catalogScroll = rememberLazyListState()
    LaunchedEffect(state.query, state.offline) { catalogScroll.scrollToItem(0) }
    LaunchedEffect(openBookId) {
        openBookId?.let {
            model.select(it, openDirectly = true)
            onOpenHandled()
        }
    }
    val preparing = state.preparing
    // Unique work is observed separately so retries and completed downloads can reopen immediately.
    val pending by
        remember(preparing?.file?.id) {
                WorkManager.getInstance(app)
                    .getWorkInfosForUniqueWorkFlow("ebook-${account.scope}-${preparing?.file?.id}")
            }
            .collectAsStateWithLifecycle(initialValue = emptyList())
    val current = pending.firstOrNull { it.id.toString() == state.workId }
    LaunchedEffect(current?.id, current?.state, preparing, state.readyToRead) {
        if (
            preparing != null &&
                (state.readyToRead || current?.state == WorkInfo.State.SUCCEEDED) &&
                app.sessions.read()?.scope == account.scope
        ) {
            app.startActivity(
                Intent(
                        app,
                        if (preparing.file.format?.lowercase() in listOf("epub", "kepub"))
                            EpubReaderActivity::class.java
                        else PageReaderActivity::class.java,
                    )
                    .apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("scope", account.scope)
                        putExtra("fileId", preparing.file.id)
                    }
            )
            model.opened()
        }
    }
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text(
            "Your reading space",
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = FontFamily.Serif,
        )
        Text("Ebooks and comics from your library.", Modifier.padding(top = 6.dp, bottom = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(
                selected = !state.offline,
                onClick = { model.offline(false) },
                label = { Text("Library") },
            )
            FilterChip(
                selected = state.offline,
                onClick = { model.offline(true) },
                label = { Text("On device") },
            )
            TextButton(onClick = { model.browse() }) { Text("Refresh ebooks") }
        }
        if (!state.offline)
            OutlinedTextField(
                value = state.query,
                onValueChange = model::search,
                placeholder = { Text("Search your ebooks") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        if (
            catalog.itemCount == 0 &&
                catalog.loadState.refresh is LoadState.NotLoading &&
                !state.busy
        )
            Text(
                if (state.offline)
                    "No ebooks saved yet. Open a book from Library to keep a copy here."
                else "No supported ebooks or comics found.",
                Modifier.padding(vertical = 24.dp),
            )
        LazyColumn(
            Modifier.weight(1f),
            state = catalogScroll,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            catalogLoading(catalog.loadState.refresh, catalog::retry, "refresh")
            catalogLoading(catalog.loadState.prepend, catalog::retry, "prepend")
            items(count = catalog.itemCount, key = catalog.itemKey { it.id }) { index ->
                val book = catalog[index] ?: return@items
                Card(
                    Modifier.fillMaxWidth()
                        .combinedClickable(
                            enabled = !state.busy && preparing == null,
                            onClick = { model.select(book.id, openDirectly = true) },
                            onLongClick = { model.select(book.id) },
                            onLongClickLabel = "Book options",
                        )
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        BookCover(
                            app,
                            account,
                            book.id,
                            book.coverVersion,
                            Modifier.size(68.dp, 88.dp),
                            ebook = true,
                        )
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(
                                book.title ?: "Untitled",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                book.authors.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            catalogLoading(catalog.loadState.append, catalog::retry, "append")
        }
    }
    state.selected
        ?.takeIf { state.options || preparing != null }
        ?.let { book ->
            ModalBottomSheet(
                modifier = Modifier.fillMaxHeight(0.95f),
                onDismissRequest = model::closeDetail,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                Column(
                    Modifier.fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(book.title ?: "Untitled", style = MaterialTheme.typography.headlineMedium)
                    BookCover(
                        app,
                        account,
                        book.id,
                        book.coverVersion,
                        Modifier.size(120.dp, 160.dp),
                        ebook = true,
                    )
                    Text(book.authors.joinToString(", ") { it.name })
                    Text(
                        "Opening a book saves a copy for offline reading. Use Sync in the reader to share progress."
                    )
                    if (preparing == null)
                        book.files
                            .filter { it.supported }
                            .forEach { file ->
                                TextButton(onClick = { model.prepare(file) }) {
                                    Text(
                                        "Read ${file.filename ?: file.format?.uppercase() ?: "EPUB"}"
                                    )
                                }
                                if (file.id in state.readyFiles)
                                    TextButton(onClick = { model.removed(file) }) {
                                        Text("Remove ${file.filename ?: "ebook"} from device")
                                    }
                            }
                    if (preparing != null) {
                        when (current?.state) {
                            WorkInfo.State.FAILED,
                            WorkInfo.State.CANCELLED -> {
                                Text(
                                    current.outputData.getString("error")
                                        ?: "Download stopped. Try again.",
                                    color = MaterialTheme.colorScheme.error,
                                )
                                TextButton(onClick = { model.prepare(preparing.file) }) {
                                    Text("Retry ebook")
                                }
                            }
                            else -> {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                val bytes = current?.progress?.getLong("bytes", 0) ?: 0
                                val total = current?.progress?.getLong("total", -1) ?: -1
                                Text(
                                    "Preparing ebook · ${Formatter.formatShortFileSize(app, bytes)}" +
                                        if (total > 0)
                                            " / ${Formatter.formatShortFileSize(app, total)}"
                                        else ""
                                )
                            }
                        }
                    }
                    BookInformation(book)
                    TextButton(onClick = model::closeDetail) { Text("Close") }
                }
            }
        }
}

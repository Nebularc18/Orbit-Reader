package app.bookorbit.community.ui

import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
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
    var grid by rememberSaveable { mutableStateOf(true) }
    val catalogScroll = rememberLazyGridState()
    LaunchedEffect(state.query, state.offline, grid, state.sort, state.readFilter) {
        catalogScroll.scrollToItem(0)
    }
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
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SegmentPicker(
                listOf("Library", "On device"),
                if (state.offline) 1 else 0,
                { model.offline(it == 1) },
                Modifier.weight(1f),
            )
            IconButton(onClick = { model.browse() }) {
                Icon(Icons.Rounded.Refresh, "Refresh ebooks", Modifier.size(20.dp))
            }
        }
        if (!state.offline)
            OutlinedTextField(
                value = state.query,
                onValueChange = model::search,
                label = { Text("Search ebooks") },
                placeholder = { Text("Search your ebooks") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
        CatalogControls(
            state.sort,
            state.readFilter,
            state.offline,
            grid,
            model::sort,
            model::readFilter,
        ) {
            grid = !grid
        }
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
            LibraryEmptyState(
                if (state.offline) "Take a book with you" else "No matching books",
                if (state.offline) "Open a book from Library to keep a copy here."
                else "Try another search or reading status.",
            )
        LazyVerticalGrid(
            columns = if (grid) adaptiveBookColumns() else GridCells.Fixed(1),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            state = catalogScroll,
            verticalArrangement = Arrangement.spacedBy(if (grid) 20.dp else 8.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            catalogLoading(catalog.loadState.refresh, catalog::retry, "refresh")
            catalogLoading(catalog.loadState.prepend, catalog::retry, "prepend")
            items(count = catalog.itemCount, key = catalog.itemKey { it.id }) { index ->
                val book = catalog[index] ?: return@items
                CatalogEntry(
                    app,
                    account,
                    book,
                    grid,
                    ebook = true,
                    modifier =
                        Modifier.fillMaxWidth()
                            .combinedClickable(
                                enabled = !state.busy && preparing == null,
                                onClick = { model.select(book.id, openDirectly = true) },
                                onLongClick = { model.select(book.id) },
                                onLongClickLabel = "Book options",
                            ),
                )
            }
            catalogLoading(catalog.loadState.append, catalog::retry, "append")
        }
    }
    state.selected
        ?.takeIf { state.options || preparing != null }
        ?.let { book ->
            ModalBottomSheet(
                modifier = Modifier.fillMaxHeight(),
                contentWindowInsets = { WindowInsets.safeDrawing },
                onDismissRequest = model::closeDetail,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                SystemBarAppearance(MaterialTheme.colorScheme.surface.luminance() > .5f)
                Column(
                    Modifier.fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BookHero(
                        app,
                        account,
                        book.id,
                        book.coverVersion,
                        book.title ?: "Untitled",
                        book.authors.joinToString(", ") { it.name },
                        ebook = true,
                    )
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
                    ManagedBookInformation(app, account, book)
                    TextButton(onClick = model::closeDetail) { Text("Close") }
                }
            }
        }
}

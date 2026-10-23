package app.bookorbit.community.ui

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import androidx.work.WorkInfo
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.BookCard
import app.bookorbit.community.data.CatalogReadFilter
import app.bookorbit.community.data.CatalogSort
import app.bookorbit.community.data.Session

@Composable
fun CatalogEntry(
    app: OrbitApplication,
    account: Session,
    book: BookCard,
    grid: Boolean,
    modifier: Modifier = Modifier,
    ebook: Boolean = false,
    status: String? = null,
) {
    if (grid)
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            BookCover(
                app,
                account,
                book.id,
                book.coverVersion,
                Modifier.fillMaxWidth()
                    .height(
                        if (
                            useNavigationRail(
                                androidx.compose.ui.platform.LocalConfiguration.current
                                    .screenWidthDp
                            )
                        )
                            220.dp
                        else 150.dp
                    ),
                ebook,
            )
            Text(
                book.title ?: "Untitled",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                book.authors.joinToString(", "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            book.readingProgress
                ?.takeIf { it > 0 }
                ?.let { CoverProgress(it, Modifier.padding(top = 3.dp)) }
        }
    else
        Surface(
            modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)),
        ) {
            Row(
                Modifier.padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BookCover(
                    app,
                    account,
                    book.id,
                    book.coverVersion,
                    Modifier.size(44.dp, 60.dp),
                    ebook,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        book.title ?: "Untitled",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        book.authors.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    status?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    book.readingProgress
                        ?.takeIf { it > 0 }
                        ?.let { CoverProgress(it, Modifier.padding(top = 4.dp)) }
                }
                Icon(
                    Icons.Rounded.ChevronRight,
                    null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
}

@Composable
fun AudioCatalog(
    app: OrbitApplication,
    state: LibraryState,
    catalog: LazyPagingItems<BookCard>,
    work: List<WorkInfo>,
    modifier: Modifier = Modifier,
    message: String?,
    onSearch: (String) -> Unit,
    onSelect: (Int) -> Unit,
    onSort: (CatalogSort) -> Unit,
    onReadFilter: (CatalogReadFilter) -> Unit,
) {
    var grid by rememberSaveable { mutableStateOf(true) }
    val scroll = rememberLazyGridState()
    LaunchedEffect(state.query, state.offline, grid, state.sort, state.readFilter) {
        scroll.scrollToItem(0)
    }
    Column(
        modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.offline)
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Storage, null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text("Available offline", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${Formatter.formatShortFileSize(app, state.usedBytes)} used · ${Formatter.formatShortFileSize(app, state.availableBytes)} free",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        else
            OutlinedTextField(
                state.query,
                onSearch,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search audiobooks") },
                placeholder = {
                    Text("Search titles or authors", style = MaterialTheme.typography.bodyMedium)
                },
                leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(20.dp)) },
                trailingIcon = {
                    if (state.query.isNotEmpty())
                        IconButton(onClick = { onSearch("") }) {
                            Icon(Icons.Rounded.Close, "Clear search", Modifier.size(18.dp))
                        }
                },
                singleLine = true,
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
            onSort,
            onReadFilter,
            if (state.offline) null else ({ grid = !grid }),
        )
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { ErrorNotice(it) }
        message?.let { ErrorNotice(it) }
        if (
            catalog.itemCount == 0 &&
                catalog.loadState.refresh is LoadState.NotLoading &&
                !state.busy
        ) {
            Column(
                Modifier.padding(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    if (state.offline) Icons.Rounded.Download else Icons.Rounded.Search,
                    null,
                    Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    if (state.offline) "Take a story with you" else "No matching audiobooks",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    if (state.offline) "Download an audiobook from Listen to keep it here."
                    else "Try another search or reading status.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyVerticalGrid(
            columns = if (grid && !state.offline) adaptiveBookColumns() else GridCells.Fixed(1),
            modifier = Modifier.weight(1f),
            state = scroll,
            contentPadding = PaddingValues(bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(if (state.offline || !grid) 8.dp else 20.dp),
        ) {
            catalogLoading(catalog.loadState.refresh, catalog::retry, "refresh")
            catalogLoading(catalog.loadState.prepend, catalog::retry, "prepend")
            items(catalog.itemCount, key = catalog.itemKey { it.id }) { index ->
                val book = catalog[index] ?: return@items
                val job =
                    work.firstOrNull { it.tags.contains("book-${book.id}") && !it.state.isFinished }
                        ?: work.firstOrNull { it.tags.contains("book-${book.id}") }
                val status =
                    if (!state.offline) null
                    else {
                        val label =
                            if (
                                book.localDownload?.complete == true ||
                                    job?.state == WorkInfo.State.SUCCEEDED
                            )
                                "Available offline"
                            else
                                when (job?.state) {
                                    WorkInfo.State.RUNNING -> "Downloading"
                                    WorkInfo.State.ENQUEUED,
                                    WorkInfo.State.BLOCKED -> "Waiting for network or retry"
                                    WorkInfo.State.FAILED -> "Failed · Tap to retry"
                                    else -> "Incomplete · Tap to resume"
                                }
                        "$label · ${Formatter.formatShortFileSize(app, maxOf(book.localDownload?.bytes ?: 0, job?.progress?.getLong("downloadedBytes", 0) ?: 0))}"
                    }
                Surface(
                    onClick = { onSelect(book.id) },
                    color = androidx.compose.ui.graphics.Color.Transparent,
                ) {
                    CatalogEntry(
                        app,
                        state.session!!,
                        book,
                        grid && !state.offline,
                        status = status,
                    )
                }
            }
            catalogLoading(catalog.loadState.append, catalog::retry, "append")
        }
    }
}

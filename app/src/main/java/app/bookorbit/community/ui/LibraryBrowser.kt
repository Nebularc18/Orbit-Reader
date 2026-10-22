package app.bookorbit.community.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.*

@Composable
fun LibraryBrowser(
    app: OrbitApplication,
    account: Session,
    modifier: Modifier = Modifier,
    onRead: (Int) -> Unit,
    onListen: (Int) -> Unit,
    onActivity: () -> Unit,
) {
    val model: LibraryBrowserViewModel = viewModel(key = "browser-${account.scope}")
    val state by model.state.collectAsStateWithLifecycle()
    val groups = model.groups.collectAsLazyPagingItems()
    val books = model.books.collectAsLazyPagingItems()
    var grid by rememberSaveable { mutableStateOf(true) }
    var orderMenu by remember { mutableStateOf(false) }
    val scroll = rememberLazyGridState()
    LaunchedEffect(state.kind, state.query, state.order, state.group?.id, grid) {
        scroll.scrollToItem(0)
    }
    BackHandler(state.group != null) { model.group(null) }
    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            state.group?.name ?: "Library",
            if (state.group == null) "Series, authors & your shelves" else state.kind.label,
            back = if (state.group != null) ({ model.group(null) }) else null,
            refresh = { if (state.group == null) groups.refresh() else books.refresh() },
            action = {
                IconButton(onClick = onActivity) {
                    Icon(Icons.Rounded.Insights, "Reading activity")
                }
            },
        )
        if (state.group == null) {
            androidx.compose.foundation.lazy.LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(LibraryGroupKind.entries.size) { index ->
                    val kind = LibraryGroupKind.entries[index]
                    FilterChip(
                        selected = state.kind == kind,
                        onClick = { model.kind(kind) },
                        label = { Text(kind.label) },
                    )
                }
            }
            OutlinedTextField(
                state.query,
                model::query,
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                placeholder = { Text("Search ${state.kind.label.lowercase()}") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (state.query.isNotEmpty())
                        IconButton(onClick = { model.query("") }) {
                            Icon(Icons.Rounded.Close, "Clear search")
                        }
                },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.group == null)
                Box {
                    TextButton(onClick = { orderMenu = true }) {
                        Text(state.order.label)
                        Icon(Icons.Rounded.ArrowDropDown, "Sort groups")
                    }
                    DropdownMenu(orderMenu, onDismissRequest = { orderMenu = false }) {
                        GroupOrder.entries
                            .filter {
                                state.kind in
                                    listOf(LibraryGroupKind.SERIES, LibraryGroupKind.AUTHORS) ||
                                    it in listOf(GroupOrder.NAME, GroupOrder.NAME_DESC)
                            }
                            .forEach { order ->
                                DropdownMenuItem(
                                    text = { Text(order.label) },
                                    onClick = {
                                        orderMenu = false
                                        model.order(order)
                                    },
                                    trailingIcon = {
                                        if (order == state.order)
                                            Icon(Icons.Rounded.Check, "Selected")
                                    },
                                )
                            }
                    }
                }
            else
                Text(
                    if (state.kind == LibraryGroupKind.SERIES) "In series order"
                    else if (state.kind == LibraryGroupKind.AUTHORS) "Titles A-Z"
                    else "Shelf order",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { grid = !grid }) {
                Icon(
                    if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                    if (grid) "List view" else "Grid view",
                )
            }
        }
        if (state.opening) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let {
            Text(it, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error)
        }
        val load = if (state.group == null) groups.loadState else books.loadState
        val count = if (state.group == null) groups.itemCount else books.itemCount
        if (count == 0 && load.refresh is LoadState.NotLoading)
            LibraryEmptyState(
                "Nothing here yet",
                if (state.group == null)
                    "Try another search, or add ${state.kind.label.lowercase()} in BookOrbit."
                else
                    "This ${state.kind.label.lowercase().removeSuffix("s")} has no books you can access.",
                Modifier.padding(20.dp),
            )
        LazyVerticalGrid(
            columns =
                if (!grid) GridCells.Fixed(1)
                else if (state.group == null) GridCells.Adaptive(145.dp)
                else GridCells.Adaptive(100.dp),
            modifier = Modifier.weight(1f),
            state = scroll,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            catalogLoading(
                load.refresh,
                { if (state.group == null) groups.retry() else books.retry() },
                "refresh",
            )
            if (state.group == null)
                items(groups.itemCount, key = groups.itemKey { it.id }) { index ->
                    groups[index]?.let { group ->
                        LibraryGroupCard(app, account, group, state.kind, grid) {
                            model.group(group)
                        }
                    }
                }
            else
                items(books.itemCount, key = books.itemKey { it.id }) { index ->
                    books[index]?.let { book ->
                        CatalogEntry(
                            app,
                            account,
                            book,
                            grid,
                            Modifier.clickable { model.book(book.id) },
                            ebook = book.files.any { it.supported },
                            status = book.readStatus?.status?.replace('_', ' '),
                        )
                    }
                }
            catalogLoading(
                load.append,
                { if (state.group == null) groups.retry() else books.retry() },
                "append",
            )
            catalogLoading(
                load.prepend,
                { if (state.group == null) groups.retry() else books.retry() },
                "prepend",
            )
        }
    }
    state.book?.let { book ->
        BrowserBookSheet(
            app,
            account,
            book,
            model::closeBook,
            {
                model.closeBook()
                onRead(book.id)
            },
            {
                model.closeBook()
                onListen(book.id)
            },
        )
    }
}

@Composable
private fun LibraryGroupCard(
    app: OrbitApplication,
    account: Session,
    group: LibraryGroup,
    kind: LibraryGroupKind,
    grid: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        val art: @Composable (Modifier) -> Unit = { modifier ->
            val coverId = group.coverId
            if (coverId != null) BookCover(app, account, coverId, "", modifier, ebook = true)
            else
                Box(modifier, contentAlignment = Alignment.Center) {
                    Icon(
                        when (kind) {
                            LibraryGroupKind.SERIES -> Icons.AutoMirrored.Rounded.LibraryBooks
                            LibraryGroupKind.AUTHORS -> Icons.Rounded.Person
                            LibraryGroupKind.COLLECTIONS -> Icons.Rounded.CollectionsBookmark
                            LibraryGroupKind.SHELVES -> Icons.Rounded.AutoAwesome
                        },
                        null,
                        Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
        }
        val labels: @Composable () -> Unit = {
            Text(
                group.name,
                style = MaterialTheme.typography.titleSmall,
                minLines = if (grid) 2 else 1,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            group.bookCount?.let {
                Text(
                    "$it ${if (it == 1) "book" else "books"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            group.readCount?.let { read ->
                if ((group.bookCount ?: 0) > 0) {
                    LinearProgressIndicator(
                        progress = { (read.toFloat() / group.bookCount!!).coerceIn(0f, 1f) },
                        Modifier.fillMaxWidth().height(3.dp),
                    )
                    Text(
                        "$read finished",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        if (grid)
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                art(Modifier.fillMaxWidth().height(125.dp))
                labels()
            }
        else
            Row(
                Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                art(Modifier.size(48.dp, 64.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    labels()
                }
                Icon(
                    Icons.Rounded.ChevronRight,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserBookSheet(
    app: OrbitApplication,
    account: Session,
    book: EbookDetail,
    onClose: () -> Unit,
    onRead: () -> Unit,
    onListen: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        modifier = Modifier.fillMaxHeight(.95f),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        SystemBarAppearance(MaterialTheme.colorScheme.surface.luminance() > .5f)
        Column(
            Modifier.fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BookHero(
                app,
                account,
                book.id,
                book.coverVersion,
                book.title ?: "Untitled",
                book.authors.joinToString(", ") { it.name },
                book.files.any { it.supported },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (book.files.any { it.supported })
                    Button(onClick = onRead, modifier = Modifier.weight(1f)) { Text("Read") }
                if (
                    book.files.any {
                        it.format?.lowercase() in setOf("m4b", "mp3", "m4a", "opus", "ogg", "flac")
                    }
                )
                    Button(onClick = onListen, modifier = Modifier.weight(1f)) { Text("Listen") }
            }
            if (
                book.files.none {
                    it.supported ||
                        it.format?.lowercase() in setOf("m4b", "mp3", "m4a", "opus", "ogg", "flac")
                }
            )
                Text("No supported reading or audio files are available.")
            BookInformation(book)
        }
    }
}

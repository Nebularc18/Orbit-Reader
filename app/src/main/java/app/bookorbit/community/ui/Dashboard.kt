package app.bookorbit.community.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.*
import java.time.LocalTime

@Composable
fun Dashboard(
    app: OrbitApplication,
    account: Session,
    modifier: Modifier = Modifier,
    onListen: (Int) -> Unit,
    onRead: (Int) -> Unit,
    opening: Boolean = false,
    openError: String? = null,
    onAppearance: () -> Unit = {},
    onActivity: () -> Unit = {},
    onLibrary: () -> Unit = {},
    onServers: () -> Unit = {},
    onNotebook: () -> Unit = {},
    onRequests: () -> Unit = {},
    onUploads: () -> Unit = {},
) {
    val model: DashboardViewModel = viewModel(key = "dashboard-${account.scope}")
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.refresh()
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) model.refresh()
        onDispose { lifecycle.removeObserver(observer) }
    }
    var showMore by remember { mutableStateOf(false) }
    val featuredRow =
        state.rows.firstOrNull { it.section != DashboardSection.SERIES && it.books.isNotEmpty() }
    val featured = featuredRow?.books?.firstOrNull()
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Orbit Reader",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        TextButton(onClick = onServers, contentPadding = PaddingValues(0.dp)) {
                            Text(
                                serverLabel(account),
                                modifier = Modifier.weight(1f, fill = false),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Icon(Icons.Rounded.ExpandMore, null, Modifier.size(16.dp))
                        }
                    }
                    IconButton(onClick = model::refresh, enabled = !state.loading) {
                        Icon(Icons.Rounded.Refresh, "Refresh dashboard", Modifier.size(22.dp))
                    }
                    IconButton(onClick = onAppearance) {
                        Icon(Icons.Rounded.Tune, "Appearance and account", Modifier.size(22.dp))
                    }
                    Box {
                        IconButton(onClick = { showMore = true }) {
                            Icon(Icons.Rounded.MoreVert, "More library tools")
                        }
                        DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                            listOf(
                                    Triple("Activity", Icons.Rounded.Insights, onActivity),
                                    Triple("Notebook", Icons.Rounded.Bookmarks, onNotebook),
                                    Triple("Book requests", Icons.Rounded.Add, onRequests),
                                    Triple("Upload a book", Icons.Rounded.Upload, onUploads),
                                )
                                .forEach { (label, icon, action) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        leadingIcon = { Icon(icon, null) },
                                        onClick = {
                                            showMore = false
                                            action()
                                        },
                                    )
                                }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val greeting =
                        when (LocalTime.now().hour) {
                            in 5..11 -> "Good morning"
                            in 12..17 -> "Good afternoon"
                            else -> "Good evening"
                        }
                    val name =
                        account.user.name
                            .ifBlank { account.user.username }
                            .trim()
                            .substringBefore(' ')
                    Text(
                        if (name.isBlank()) greeting else "$greeting, $name",
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        "Pick up where you left off.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            openError?.let {
                Text(
                    it,
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.loading || opening)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
        }
        featured?.let { book ->
            item(key = "featured") {
                val audio = featuredRow.section == DashboardSection.LISTENING
                Surface(
                    modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            BookCover(
                                app,
                                account,
                                book.id,
                                book.coverVersion,
                                Modifier.size(100.dp, 144.dp).clickable {
                                    if (audio) onListen(book.id) else onRead(book.id)
                                },
                                ebook = !audio,
                            )
                            Column(
                                Modifier.weight(1f).heightIn(min = 144.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Icon(
                                        if (audio) Icons.Rounded.Headphones
                                        else Icons.Rounded.AutoStories,
                                        null,
                                        Modifier.size(13.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        if (audio) "Continue listening" else "Continue reading",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    book.title ?: "Untitled",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontFamily = FontFamily.Serif,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    book.authors.joinToString(", "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.weight(1f))
                                book.readingProgress
                                    ?.takeIf { it > 0 }
                                    ?.let { progress ->
                                        Text(
                                            "${progress.toInt().coerceIn(0, 100)}% complete",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        CoverProgress(progress)
                                    }
                            }
                        }
                        Button(
                            onClick = { if (audio) onListen(book.id) else onRead(book.id) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        ) {
                            Icon(
                                if (audio) Icons.Rounded.Headphones
                                else Icons.AutoMirrored.Rounded.MenuBook,
                                null,
                                Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(if (audio) "Open audiobook" else "Continue reading")
                        }
                    }
                }
            }
        }
        if (state.goal != null || state.streak != null)
            item(key = "reading-activity") {
                ReadingActivityCard(
                    state.goal,
                    state.streak,
                    Modifier.padding(horizontal = 24.dp).clickable(onClick = onActivity),
                )
            }
        if (!state.loading && state.rows.all { it.books.isEmpty() })
            item {
                Surface(
                    Modifier.padding(horizontal = 24.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(
                        Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Rounded.AutoStories,
                            null,
                            Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "Your next book starts here",
                            style = MaterialTheme.typography.titleLarge,
                            fontFamily = FontFamily.Serif,
                        )
                        Text(
                            "Browse your library to find something to read or listen to.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onLibrary) { Text("Browse library") }
                    }
                }
            }
        items(
            state.rows.filter { row ->
                row.error ||
                    row.books.any { it.id != featured?.id || row.section != featuredRow?.section }
            },
            key = { it.section },
        ) { row ->
            val books =
                if (row.section == featuredRow?.section) row.books.filter { it.id != featured?.id }
                else row.books
            if (books.isNotEmpty() || row.error)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        Modifier.padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            when (row.section) {
                                DashboardSection.LISTENING -> Icons.Rounded.Headphones
                                DashboardSection.READING -> Icons.Rounded.Bookmark
                                else -> Icons.Rounded.AutoStories
                            },
                            null,
                            Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            row.section.title,
                            modifier = Modifier.weight(1f).semantics { heading() },
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    if (row.error)
                        Text(
                            "Could not refresh. Showing activity saved on this phone.",
                            Modifier.padding(horizontal = 24.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(books, key = { it.id }) { book ->
                            val audio = row.section == DashboardSection.LISTENING
                            val canRead = book.files.any { it.supported }
                            val canListen =
                                book.files.any {
                                    it.format?.lowercase() in
                                        setOf("m4b", "mp3", "m4a", "opus", "ogg", "flac")
                                }
                            Column(
                                Modifier.width(136.dp).clickable(
                                    enabled =
                                        row.section != DashboardSection.SERIES ||
                                            canRead ||
                                            canListen
                                ) {
                                    if (audio || row.section == DashboardSection.SERIES && !canRead)
                                        onListen(book.id)
                                    else onRead(book.id)
                                },
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                BookCover(
                                    app,
                                    account,
                                    book.id,
                                    book.coverVersion,
                                    Modifier.size(136.dp, 180.dp),
                                    ebook = !audio,
                                )
                                Text(
                                    book.title ?: "Untitled",
                                    style = MaterialTheme.typography.titleSmall,
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
                                    ?.let { progress ->
                                        CoverProgress(progress, Modifier.padding(top = 3.dp))
                                        Text(
                                            "${progress.toInt().coerceIn(0, 100)}% complete",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                if (
                                    row.section == DashboardSection.SERIES && canRead && canListen
                                ) {
                                    TextButton(
                                        onClick = { onListen(book.id) },
                                        contentPadding = PaddingValues(0.dp),
                                    ) {
                                        Text("Listen", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
        }
    }
}

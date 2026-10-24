package app.bookorbit.community.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
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
    val featuredRow =
        state.rows.firstOrNull { it.section != DashboardSection.SERIES && it.books.isNotEmpty() }
    val featured = featuredRow?.books?.firstOrNull()
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        serverLabel(account),
                        modifier = Modifier.clickable(onClick = onServers),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.6.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
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
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onActivity) {
                    Icon(Icons.Rounded.Insights, "Reading activity", Modifier.size(20.dp))
                }
                IconButton(onClick = model::refresh, enabled = !state.loading) {
                    Icon(Icons.Rounded.Refresh, "Refresh dashboard", Modifier.size(20.dp))
                }
                CircleAction(Icons.Rounded.Tune, "Appearance and account", onAppearance)
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
                    modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border =
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .26f)),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            BookCover(
                                app,
                                account,
                                book.id,
                                book.coverVersion,
                                Modifier.size(90.dp, 128.dp).clickable {
                                    if (audio) onListen(book.id) else onRead(book.id)
                                },
                                ebook = !audio,
                            )
                            Column(
                                Modifier.weight(1f).heightIn(min = 128.dp),
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
                                        if (audio) "CONTINUE LISTENING" else "CONTINUE READING",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        letterSpacing = .6.sp,
                                    )
                                }
                                Text(
                                    book.title ?: "Untitled",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
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
                        FilledTonalButton(
                            onClick = { if (audio) onListen(book.id) else onRead(book.id) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                            colors =
                                ButtonDefaults.filledTonalButtonColors(
                                    containerColor =
                                        MaterialTheme.colorScheme.primary.copy(alpha = .14f),
                                    contentColor = MaterialTheme.colorScheme.primary,
                                ),
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
        item(key = "library-shortcuts") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onLibrary, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Rounded.LibraryBooks, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Browse library")
                }
                OutlinedButton(onClick = onActivity, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Insights, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Activity")
                }
            }
        }
        item(key = "notebook-shortcut") {
            OutlinedButton(
                onClick = onNotebook,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            ) {
                Icon(Icons.Rounded.Bookmarks, null, Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("Notebook")
            }
        }
        item(key = "requests-shortcut") {
            OutlinedButton(
                onClick = onRequests,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            ) {
                Icon(Icons.Rounded.Add, null, Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("Book requests")
            }
        }
        item(key = "uploads-shortcut") {
            OutlinedButton(
                onClick = onUploads,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            ) {
                Text("Upload a book")
            }
        }
        if (state.goal != null || state.streak != null)
            item(key = "reading-activity") {
                ReadingActivityCard(
                    state.goal,
                    state.streak,
                    Modifier.padding(horizontal = 20.dp).clickable(onClick = onActivity),
                )
            }
        if (!state.loading && state.rows.all { it.books.isEmpty() })
            item {
                Surface(
                    Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
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
                            "A little space for your next book",
                            style = MaterialTheme.typography.titleLarge,
                            fontFamily = FontFamily.Serif,
                        )
                        Text(
                            "Open a book from Read or Listen. Your unfinished books will be waiting here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                        Modifier.padding(horizontal = 20.dp),
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
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        HorizontalDivider(
                            Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    if (row.error)
                        Text(
                            "Could not refresh. Showing activity saved on this phone.",
                            Modifier.padding(horizontal = 20.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
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
                                Modifier.width(112.dp).clickable(
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
                                    Modifier.size(112.dp, 154.dp),
                                    ebook = !audio,
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

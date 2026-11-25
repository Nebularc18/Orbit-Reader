package app.bookorbit.community.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
    onResume: (Int) -> Unit,
    onDownloads: () -> Unit,
    onSignIn: (() -> Unit)? = null,
    playbackMessage: String? = null,
    opening: Boolean = false,
    openError: String? = null,
    onAppearance: () -> Unit = {},
    onActivity: () -> Unit = {},
    onLibrary: () -> Unit = {},
    onNotebook: () -> Unit = {},
    onHistory: () -> Unit = {},
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
        contentPadding = PaddingValues(top = 8.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
                        modifier = Modifier.weight(1f).semantics { heading() },
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(onClick = model::refresh) {
                        Icon(Icons.Rounded.Refresh, "Refresh home")
                    }
                    IconButton(onClick = onAppearance) {
                        Icon(Icons.Rounded.Tune, "Appearance and account")
                    }
                }
                if (onSignIn != null) OfflineSignIn(onSignIn, onDownloads)
            }
            (playbackMessage ?: openError)?.let {
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
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ResumeCard(
                        app,
                        account,
                        book,
                        audio,
                        onCover = { if (audio) onListen(book.id) else onRead(book.id) },
                        onResume = { if (audio) onResume(book.id) else onRead(book.id) },
                    )
                }
            }
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
                            modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.headlineSmall,
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
                                    Modifier.size(136.dp, if (audio) 136.dp else 180.dp),
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
        item(key = "reading-tools") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilledTonalButton(
                    onClick = onNotebook,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    Icon(Icons.Rounded.Bookmarks, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Notebook")
                }
                FilledTonalButton(
                    onClick = onHistory,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    Icon(Icons.Rounded.History, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("History")
                }
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
    }
}

@Composable
private fun ResumeCard(
    app: OrbitApplication,
    account: Session,
    book: BookCard,
    audio: Boolean,
    onCover: () -> Unit,
    onResume: () -> Unit,
) {
    var coverColor by
        remember(account.scope, book.id, book.coverVersion) { mutableStateOf<Color?>(null) }
    val colors = MaterialTheme.colorScheme
    val accent = coverColor ?: colors.primary
    val dark = colors.surface.luminance() < .5f
    val tinted =
        accent.copy(alpha = if (dark) .34f else .19f).compositeOver(colors.surfaceContainer)
    Surface(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Box {
            Canvas(Modifier.matchParentSize()) {
                drawRect(Brush.verticalGradient(listOf(tinted, colors.surfaceContainerLow)))
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BookCover(
                        app,
                        account,
                        book.id,
                        book.coverVersion,
                        Modifier.size(width = 110.dp, height = if (audio) 110.dp else 146.dp)
                            .clickable(
                                role = Role.Button,
                                onClickLabel =
                                    if (audio) "Open audiobook details" else "Open ebook",
                                onClick = onCover,
                            ),
                        ebook = !audio,
                        onColor = { coverColor = it },
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (audio) "Listening" else "Reading",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.primary,
                        )
                        Text(
                            book.title ?: "Untitled",
                            style = MaterialTheme.typography.titleLarge,
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.Bold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (book.authors.isNotEmpty())
                            Text(
                                book.authors.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        book.readingProgress
                            ?.takeIf { it.isFinite() && it > 0 }
                            ?.let { progress ->
                                CoverProgress(progress, Modifier.padding(top = 4.dp))
                                Text(
                                    "${progress.toInt().coerceIn(0, 100)}% complete",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                    }
                }
                FilledTonalButton(
                    onClick = onResume,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Icon(
                        if (audio) Icons.Rounded.PlayArrow else Icons.AutoMirrored.Rounded.MenuBook,
                        null,
                        Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (audio) "Resume listening" else "Continue reading")
                }
            }
        }
    }
}

@Composable
internal fun OfflineSignIn(onSignIn: () -> Unit, onDownloads: (() -> Unit)? = null) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Saved books are available offline",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (onDownloads != null) {
                IconButton(onClick = onDownloads) { Icon(Icons.Rounded.Download, "View downloads") }
            }
            TextButton(onClick = onSignIn) { Text("Sign in") }
        }
    }
}

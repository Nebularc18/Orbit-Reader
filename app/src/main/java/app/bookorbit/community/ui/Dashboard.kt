package app.bookorbit.community.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.*

@Composable
fun Dashboard(
    app: OrbitApplication,
    account: Session,
    modifier: Modifier = Modifier,
    onListen: (Int) -> Unit,
    onRead: (Int) -> Unit,
    opening: Boolean = false,
    openError: String? = null,
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
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Your next chapter",
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = FontFamily.Serif,
                    )
                    Text(
                        "Pick up where you left off.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = model::refresh, enabled = !state.loading) {
                    Icon(Icons.Rounded.Refresh, "Refresh dashboard")
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
        items(state.rows, key = { it.section }) { row ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    row.section.title,
                    Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (row.error)
                    Text(
                        if (row.books.isEmpty()) "Could not refresh this section."
                        else
                            "Could not refresh this section. Showing activity saved on this phone.",
                        Modifier.padding(horizontal = 20.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                if (row.books.isEmpty())
                    Text(
                        if (row.error) "Connect to your server and tap Refresh."
                        else row.section.empty,
                        Modifier.padding(horizontal = 20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                else
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(row.books, key = { it.id }) { book ->
                            val canRead = book.files.any { it.supported }
                            val canListen =
                                book.files.any {
                                    it.format?.lowercase() in
                                        setOf("m4b", "mp3", "m4a", "opus", "ogg", "flac")
                                }
                            val audio = row.section == DashboardSection.LISTENING
                            Column(
                                Modifier.width(140.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                BookCover(
                                    app,
                                    account,
                                    book.id,
                                    book.coverVersion,
                                    Modifier.size(140.dp, 180.dp).clickable(
                                        enabled =
                                            row.section != DashboardSection.SERIES ||
                                                canRead ||
                                                canListen
                                    ) {
                                        if (
                                            audio ||
                                                (row.section == DashboardSection.SERIES && !canRead)
                                        )
                                            onListen(book.id)
                                        else onRead(book.id)
                                    },
                                    ebook = !audio,
                                )
                                Text(
                                    book.title ?: "Untitled",
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    book.authors.joinToString(", "),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (row.section == DashboardSection.SERIES) {
                                    book.seriesName?.let {
                                        Text(
                                            it,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                    Row {
                                        if (canRead)
                                            TextButton(onClick = { onRead(book.id) }) {
                                                Text("Read")
                                            }
                                        if (
                                            book.files.any {
                                                it.format?.lowercase() in
                                                    setOf(
                                                        "m4b",
                                                        "mp3",
                                                        "m4a",
                                                        "opus",
                                                        "ogg",
                                                        "flac",
                                                    )
                                            }
                                        )
                                            TextButton(onClick = { onListen(book.id) }) {
                                                Text("Listen")
                                            }
                                    }
                                } else
                                    TextButton(
                                        onClick = {
                                            if (audio) onListen(book.id) else onRead(book.id)
                                        }
                                    ) {
                                        Text(if (audio) "Listen" else "Read")
                                    }
                            }
                        }
                    }
            }
        }
    }
}

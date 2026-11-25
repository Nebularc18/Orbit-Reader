package app.bookorbit.community.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.BOOK_PAGE_SIZE
import app.bookorbit.community.data.BookCard
import app.bookorbit.community.data.Session
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun ReadingHistoryScreen(
    app: OrbitApplication,
    account: Session,
    modifier: Modifier = Modifier,
    onSignIn: (() -> Unit)? = null,
    onBack: () -> Unit,
) {
    val model: ReadingHistoryViewModel = viewModel(key = "history-${account.scope}")
    val state by model.state.collectAsStateWithLifecycle()
    var query by rememberSaveable(account.scope) { mutableStateOf("") }
    var year by rememberSaveable(account.scope) { mutableStateOf<Int?>(null) }
    var page by rememberSaveable(account.scope) { mutableIntStateOf(1) }
    var yearMenu by remember { mutableStateOf(false) }
    LaunchedEffect(model) { model.refreshYears() }
    LaunchedEffect(model, query, year, page) { model.refresh(query, year, page) }
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            Modifier.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Reading history", style = MaterialTheme.typography.headlineSmall)
        }
        if (onSignIn != null) OfflineSignIn(onSignIn)
        OutlinedTextField(
            query,
            {
                query = it.take(200)
                page = 1
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search finished books") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
        )
        if (state.years.isNotEmpty() || year != null)
            Box {
                TextButton(onClick = { yearMenu = true }) { Text(year?.toString() ?: "All time") }
                DropdownMenu(expanded = yearMenu, onDismissRequest = { yearMenu = false }) {
                    (listOf<Int?>(null) + state.years).forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option?.toString() ?: "All time") },
                            onClick = {
                                year = option
                                page = 1
                                yearMenu = false
                            },
                        )
                    }
                }
            }
        when {
            state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            state.error != null ->
                LibraryErrorState(state.error!!) {
                    model.refreshYears()
                    model.refresh(query, year, page)
                }
            else -> {
                val result = state.results ?: return@Column
                if (result.items.isEmpty()) {
                    Text("No finished books found.", Modifier.padding(vertical = 16.dp))
                }
                key(page) {
                    LazyColumn(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(result.items, key = { it.id }) { item ->
                            val date =
                                item.endedOn?.let {
                                    runCatching {
                                            LocalDate.parse(it.take(10))
                                                .format(
                                                    DateTimeFormatter.ofLocalizedDate(
                                                        FormatStyle.MEDIUM
                                                    )
                                                )
                                        }
                                        .getOrNull()
                                }
                            val info =
                                listOfNotNull(
                                        date ?: "Finish date unavailable",
                                        item.format?.uppercase(),
                                        item.readNumber?.takeIf { it > 1 }?.let { "Read $it" },
                                    )
                                    .joinToString(" · ")
                            if (item.bookId != null)
                                CatalogEntry(
                                    app,
                                    account,
                                    BookCard(
                                        item.bookId,
                                        item.title,
                                        item.authors,
                                        coverVersion = item.coverVersion.orEmpty(),
                                    ),
                                    grid = false,
                                    ebook =
                                        item.format?.lowercase() !in
                                            setOf("m4b", "mp3", "m4a", "opus", "ogg", "flac"),
                                    status = info,
                                    showChevron = false,
                                )
                            else
                                Column(Modifier.padding(vertical = 8.dp)) {
                                    Text(
                                        item.title ?: "Untitled",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(item.authors.joinToString(", "))
                                    Text(info, style = MaterialTheme.typography.bodySmall)
                                }
                        }
                    }
                }
                if (page > 1 || result.total > BOOK_PAGE_SIZE)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { page-- }, enabled = page > 1) { Text("Previous") }
                        Text("Page $page")
                        TextButton(
                            onClick = { page++ },
                            enabled = page.toLong() * BOOK_PAGE_SIZE < result.total,
                        ) {
                            Text("Next")
                        }
                    }
            }
        }
    }
}

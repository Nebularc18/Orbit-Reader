package app.bookorbit.community.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.bookorbit.community.data.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookRequestsScreen(account: Session, modifier: Modifier = Modifier, onBack: () -> Unit) {
    if (!account.user.isSuperuser && "book_request_access" !in account.user.permissions) {
        Column(modifier.fillMaxSize()) {
            ScreenHeader("Book requests", "Your server requests", back = onBack)
            LibraryEmptyState(
                "Book requests need permission",
                "Ask your server administrator to enable book request access for your account.",
                Modifier.padding(20.dp),
            )
        }
        return
    }
    val model: BookRequestsViewModel = viewModel(key = "book-requests-${account.scope}")
    val state by model.state.collectAsStateWithLifecycle()
    var form by rememberSaveable(account.scope) { mutableStateOf(false) }
    var lastSubmitted by rememberSaveable(account.scope) { mutableIntStateOf(state.submitted) }
    val formOpen by rememberUpdatedState(form)
    var cancelling by remember(account.scope) { mutableStateOf<BookRequest?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !formOpen) model.refresh()
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !formOpen) model.refresh()
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.submitted) {
        if (state.submitted > lastSubmitted) form = false
        lastSubmitted = state.submitted
    }
    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            "Book requests",
            "Requests you made or joined",
            back = onBack,
            refresh = model::refresh,
        )
        FlowRow(
            Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { form = true }, enabled = !state.busy) { Text("Request a book") }
            RequestChoice("Status", state.status, requestStatuses, !state.busy) {
                model.filters(it, state.media)
            }
            RequestChoice("Format", state.media, listOf("ebook", "audiobook"), !state.busy) {
                model.filters(state.status, it)
            }
        }
        state.message?.let {
            Text(it, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.primary)
        }
        state.error?.let { Box(Modifier.padding(20.dp)) { LibraryErrorState(it, model::refresh) } }
        if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.loading && state.error == null && state.items.isEmpty())
                item {
                    LibraryEmptyState(
                        "No requests here",
                        "Submit a title or change the status and format filters.",
                    )
                }
            items(state.items, key = { it.id }) { request ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(request.title, style = MaterialTheme.typography.titleMedium)
                        if (request.authors.isNotEmpty()) Text(request.authors.joinToString(", "))
                        Text(
                            "${request.mediaKind.replaceFirstChar(Char::uppercase)} · ${request.status.replace('_', ' ')}",
                            color = MaterialTheme.colorScheme.primary,
                        )
                        request.note?.takeIf(String::isNotBlank)?.let { Text(it) }
                        request.decisionNote?.takeIf(String::isNotBlank)?.let {
                            Text("Decision: $it")
                        }
                        request.statusReason?.takeIf(String::isNotBlank)?.let { Text(it) }
                        if (request.canCancel(account.user))
                            TextButton(onClick = { cancelling = request }, enabled = !state.busy) {
                                Text("Cancel request")
                            }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(
                onClick = { model.page(-1) },
                enabled = !state.loading && !state.busy && state.page > 1,
            ) {
                Text("Previous")
            }
            Text("Page ${state.page}", Modifier.padding(12.dp))
            TextButton(
                onClick = { model.page(1) },
                enabled = !state.loading && !state.busy && state.page.toLong() * 30 < state.total,
            ) {
                Text("Next")
            }
        }
    }
    if (form)
        RequestBookForm(account.scope, state.busy, state.error, { form = false }, model::submit)
    cancelling?.let { value ->
        AlertDialog(
            onDismissRequest = { cancelling = null },
            title = { Text("Cancel this request?") },
            text = {
                Text(
                    "Cancel ${value.title}? This can also stop an active transfer for everyone following the request."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        cancelling = null
                        model.cancel(value)
                    }
                ) {
                    Text("Cancel request")
                }
            },
            dismissButton = { TextButton(onClick = { cancelling = null }) { Text("Keep request") } },
        )
    }
}

@Composable
private fun RequestChoice(
    label: String,
    value: String?,
    choices: List<String>,
    enabled: Boolean,
    onChoose: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled) {
            Text("$label: ${value?.replace('_', ' ') ?: "All"}")
        }
        DropdownMenu(open, { open = false }) {
            (listOf<String?>(null) + choices).forEach { item ->
                DropdownMenuItem(
                    text = { Text(item?.replace('_', ' ') ?: "All") },
                    onClick = {
                        open = false
                        onChoose(item)
                    },
                )
            }
        }
    }
}

@Composable
private fun RequestBookForm(
    owner: String,
    busy: Boolean,
    error: String?,
    onClose: () -> Unit,
    submit: (NewBookRequest) -> Unit,
) {
    var title by rememberSaveable(owner) { mutableStateOf("") }
    var author by rememberSaveable(owner) { mutableStateOf("") }
    var media by rememberSaveable(owner) { mutableStateOf("ebook") }
    var note by rememberSaveable(owner) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Request a book") },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Send a title to your server's request queue. Requests follow its permissions and approval rules."
                )
                OutlinedTextField(
                    title,
                    { title = it.take(500) },
                    label = { Text("Title") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    author,
                    { author = it.take(255) },
                    label = { Text("Author (optional)") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ebook", "audiobook").forEach { value ->
                        FilterChip(
                            selected = media == value,
                            onClick = { media = value },
                            enabled = !busy,
                            label = { Text(value.replaceFirstChar(Char::uppercase)) },
                        )
                    }
                }
                OutlinedTextField(
                    note,
                    { note = it.take(2000) },
                    label = { Text("Note (optional)") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && title.isNotBlank(),
                onClick = {
                    submit(
                        NewBookRequest(
                            title,
                            listOf(author).filter(String::isNotBlank),
                            media,
                            note,
                        )
                    )
                },
            ) {
                Text(if (busy) "Submitting…" else "Submit request")
            }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("Close") } },
    )
}

package app.bookorbit.community.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.*
import app.bookorbit.community.playback.*
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrbitApp(model: LibraryViewModel, app: OrbitApplication, controller: MediaController?) {
    val state by model.state.collectAsStateWithLifecycle()
    val work by
        remember(state.session?.scope) {
                WorkManager.getInstance(app)
                    .getWorkInfosByTagFlow("downloads-${state.session?.scope}")
            }
            .collectAsStateWithLifecycle(initialValue = emptyList())
    LaunchedEffect(work) { model.refreshDownloaded() }
    val current by app.currentManifest.collectAsStateWithLifecycle()
    val message by app.playbackMessage.collectAsStateWithLifecycle()
    val conflict by app.progressConflict.collectAsStateWithLifecycle()
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    var signOut by remember { mutableStateOf(false) }
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val colors =
        if (androidx.compose.foundation.isSystemInDarkTheme())
            darkColorScheme(primary = Color(0xFFAFD3B5), secondary = Color(0xFFD9C4A0))
        else
            lightColorScheme(
                primary = Color(0xFF304F47),
                secondary = Color(0xFF765B35),
                background = Color(0xFFFAF8F2),
                surface = Color(0xFFFAF8F2),
            )
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize()) {
            if (state.session == null) SignIn(state.busy, state.error, model::login)
            else
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    "Orbit Reader",
                                    fontFamily = FontFamily.Serif,
                                    fontWeight = FontWeight.Bold,
                                )
                            },
                            actions = {
                                IconButton(onClick = { model.browse() }) {
                                    Icon(Icons.Rounded.Refresh, "Refresh")
                                }
                                IconButton(onClick = { signOut = true }) {
                                    Icon(Icons.AutoMirrored.Rounded.Logout, "Sign out")
                                }
                            },
                        )
                    },
                    bottomBar = {
                        Column {
                            if (current != null)
                                Surface(
                                    tonalElevation = 4.dp,
                                    modifier =
                                        Modifier.fillMaxWidth().clickable { showPlayer = true },
                                ) {
                                    Row(
                                        Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Rounded.Headphones, null)
                                        Text(
                                            current!!.book.title,
                                            Modifier.weight(1f).padding(horizontal = 12.dp),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Icon(Icons.Rounded.ExpandLess, "Open player")
                                    }
                                }
                            NavigationBar {
                                NavigationBarItem(
                                    selected = !state.offline,
                                    onClick = { model.setOffline(false) },
                                    icon = { Icon(Icons.Rounded.Headphones, null) },
                                    label = { Text("Listen") },
                                )
                                NavigationBarItem(
                                    selected = state.offline,
                                    onClick = { model.setOffline(true) },
                                    icon = { Icon(Icons.Rounded.DownloadDone, null) },
                                    label = { Text("Downloads") },
                                )
                            }
                        }
                    },
                ) { padding ->
                    Column(
                        Modifier.padding(padding)
                            .widthIn(max = 840.dp)
                            .fillMaxSize()
                            .padding(horizontal = 20.dp)
                    ) {
                        Text(
                            if (state.offline) "Your downloads" else "A good book. Anywhere.",
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = FontFamily.Serif,
                        )
                        Text(
                            if (state.offline) "Downloaded books play without your server."
                            else
                                "Audiobooks from ${state.session!!.user.name.ifBlank { state.session!!.user.username }}'s library",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
                        )
                        if (state.offline)
                            Text(
                                "${Formatter.formatShortFileSize(app, state.usedBytes)} used · ${Formatter.formatShortFileSize(app, state.availableBytes)} free on device",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(bottom = 12.dp),
                            )
                        if (!state.offline)
                            OutlinedTextField(
                                value = state.query,
                                onValueChange = model::search,
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("Search your audiobooks") },
                                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                                singleLine = true,
                                shape = RoundedCornerShape(20.dp),
                            )
                        if (state.busy)
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                        state.error?.let { ErrorNotice(it) }
                        message?.let { ErrorNotice(it) }
                        if (state.books?.items?.isEmpty() == true && !state.busy)
                            Column(Modifier.padding(vertical = 40.dp)) {
                                Icon(
                                    if (state.offline) Icons.Rounded.Download
                                    else Icons.Rounded.AutoStories,
                                    null,
                                    Modifier.size(44.dp),
                                )
                                Text(
                                    if (state.offline) "Your next journey starts here"
                                    else "No audiobooks found",
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.padding(top = 16.dp),
                                )
                                Text(
                                    if (state.offline)
                                        "Download a book from Listen, then take it with you."
                                    else
                                        "Try a different search or add an audiobook to your server.",
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        LazyColumn(
                            Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(state.books?.items.orEmpty(), key = { it.id }) { book ->
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    tonalElevation = 1.dp,
                                    modifier =
                                        Modifier.fillMaxWidth().clickable { model.select(book.id) },
                                ) {
                                    Row(
                                        Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        BookCover(
                                            app,
                                            state.session!!,
                                            book.id,
                                            book.coverVersion,
                                            Modifier.size(68.dp, 88.dp),
                                        )
                                        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                                            Text(
                                                book.title ?: "Untitled",
                                                style = MaterialTheme.typography.titleMedium,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                book.authors.joinToString(", "),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                            )
                                            if (state.offline) {
                                                val download =
                                                    state.downloads.firstOrNull {
                                                        it.manifest.book.id == book.id
                                                    }
                                                val job =
                                                    work.firstOrNull {
                                                        it.tags.contains("book-${book.id}") &&
                                                            !it.state.isFinished
                                                    }
                                                        ?: work.firstOrNull {
                                                            it.tags.contains("book-${book.id}")
                                                        }
                                                Text(
                                                    (if (download?.complete == true)
                                                        "Available offline"
                                                    else
                                                        when (job?.state) {
                                                            WorkInfo.State.RUNNING -> "Downloading"
                                                            WorkInfo.State.ENQUEUED,
                                                            WorkInfo.State.BLOCKED ->
                                                                "Waiting for network or retry"
                                                            WorkInfo.State.FAILED ->
                                                                "Failed · Tap to retry"
                                                            else -> "Incomplete · Tap to resume"
                                                        }) +
                                                        " · ${Formatter.formatShortFileSize(app, download?.bytes ?: 0)}",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    modifier = Modifier.padding(top = 8.dp),
                                                )
                                            }
                                            book.readingProgress
                                                ?.takeIf { it > 0 }
                                                ?.let { progress ->
                                                    LinearProgressIndicator(
                                                        progress = {
                                                            (progress / 100)
                                                                .toFloat()
                                                                .coerceIn(0f, 1f)
                                                        },
                                                        modifier =
                                                            Modifier.fillMaxWidth()
                                                                .padding(top = 12.dp),
                                                    )
                                                }
                                        }
                                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null)
                                    }
                                }
                            }
                        }
                        if (state.books != null)
                            Row(
                                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    onClick = { model.page(-1) },
                                    enabled = state.page > 1 && !state.busy,
                                ) {
                                    Text("Previous")
                                }
                                Text(
                                    "Page ${state.page}" +
                                        if (state.books!!.total >= 0)
                                            " · ${state.books!!.total} books"
                                        else "",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                TextButton(
                                    onClick = { model.page(1) },
                                    enabled =
                                        !state.busy &&
                                            (if (state.offline) state.books!!.items.size == 30
                                            else state.page * 30 < state.books!!.total),
                                ) {
                                    Text("Next")
                                }
                            }
                    }
                }
            state.selected?.let { book ->
                BookSheet(
                    book,
                    state,
                    app,
                    model,
                    controller,
                    onPlay = {
                        if (Build.VERSION.SDK_INT >= 33)
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        openBook(controller, book.book.id, offline = state.offline)
                        model.closeDetail()
                        showPlayer = true
                    },
                )
            }
            if (showPlayer && current != null && controller != null)
                ModalBottomSheet(
                    onDismissRequest = { showPlayer = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                ) {
                    PlayerSheet(current!!, controller, app)
                }
            if (conflict != null)
                AlertDialog(
                    onDismissRequest = { app.progressConflict.value = null },
                    title = { Text("Where would you like to resume?") },
                    text = {
                        Text(
                            "This device has unsynced listening progress, and the server position changed on another device. Choose which position to keep."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { openBook(controller, conflict!!, "local") }) {
                            Text("Use this device")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { openBook(controller, conflict!!, "server") }) {
                            Text("Use server position")
                        }
                    },
                )
            if (signOut)
                AlertDialog(
                    onDismissRequest = { signOut = false },
                    title = { Text("Sign out?") },
                    text = {
                        Text(
                            "Playback will stop. Downloads stay private on this device and are available when you sign in to this account again."
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                controller?.stop()
                                controller?.clearMediaItems()
                                app.stopService(
                                    android.content.Intent(app, PlaybackService::class.java)
                                )
                                model.logout {}
                                signOut = false
                            }
                        ) {
                            Text("Sign out")
                        }
                    },
                    dismissButton = { TextButton(onClick = { signOut = false }) { Text("Cancel") } },
                )
        }
    }
}

@Composable
private fun SignIn(busy: Boolean, error: String?, login: (String, String, String) -> Unit) {
    var server by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(
                Icons.Rounded.AutoStories,
                null,
                Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Your library.\nYour listening space.",
                style = MaterialTheme.typography.headlineLarge,
                fontFamily = FontFamily.Serif,
            )
            Text(
                "Connect Orbit Reader to your BookOrbit server.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                server,
                { server = it },
                label = { Text("Server address") },
                placeholder = { Text("https://books.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
            if (server.trim().startsWith("http://"))
                Text(
                    "HTTP sends your password and books without encryption. Use it only on a trusted local network.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            OutlinedTextField(
                username,
                { username = it },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
            OutlinedTextField(
                password,
                { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
            error?.let { ErrorNotice(it) }
            Button(
                onClick = {
                    val supplied = password
                    password = ""
                    login(server, username, supplied)
                },
                enabled =
                    !busy && server.isNotBlank() && username.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (busy) "Connecting…" else "Connect to library")
            }
            Text(
                "An independent, open-source BookOrbit client. Password sign-in is supported in this first version.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorNotice(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Text(
            message,
            Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun BookCover(
    app: OrbitApplication,
    session: Session,
    bookId: Int,
    version: String,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val loader =
        remember(app) {
            ImageLoader.Builder(context).okHttpClient(app.api.authenticatedClient).build()
        }
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Headphones, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        AsyncImage(
            model =
                ImageRequest.Builder(context)
                    .data("${session.server}/api/v1/books/$bookId/thumbnail")
                    .memoryCacheKey("${session.scope}/$bookId/$version")
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .build(),
            imageLoader = loader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

fun openBook(
    controller: MediaController?,
    bookId: Int,
    resolution: String? = null,
    offline: Boolean = false,
) {
    controller?.sendCustomCommand(
        SessionCommand(OPEN_BOOK, Bundle.EMPTY),
        Bundle().apply {
            putInt("bookId", bookId)
            putString("resolution", resolution)
            putBoolean("offline", offline)
        },
    )
}

fun duration(value: Long): String {
    val seconds = (value / 1000).coerceAtLeast(0)
    return if (seconds >= 3600)
        "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}

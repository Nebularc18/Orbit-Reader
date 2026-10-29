package app.bookorbit.community.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.work.WorkManager
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.*
import app.bookorbit.community.playback.*
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrbitApp(
    model: LibraryViewModel,
    app: OrbitApplication,
    controller: MediaController?,
    widgetTab: Int? = null,
    consumeWidget: () -> Unit = {},
) {
    val state by model.state.collectAsStateWithLifecycle()
    val catalog = model.catalog.collectAsLazyPagingItems()
    val work by
        remember(state.session?.scope) {
                WorkManager.getInstance(app)
                    .getWorkInfosByTagFlow("downloads-${state.session?.scope}")
            }
            .collectAsStateWithLifecycle(initialValue = emptyList())
    LaunchedEffect(work) { model.refreshDownloaded() }
    LaunchedEffect(state.offline, work.map { it.id to it.state }) {
        if (state.offline) catalog.refresh()
    }
    val current by app.currentManifest.collectAsStateWithLifecycle()
    val message by app.playbackMessage.collectAsStateWithLifecycle()
    val conflict by app.progressConflict.collectAsStateWithLifecycle()
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    var showDashboard by rememberSaveable(state.session?.scope) { mutableStateOf(true) }
    var pendingEbook by remember { mutableStateOf<Int?>(null) }
    var showLibrary by rememberSaveable(state.session?.scope) { mutableStateOf(false) }
    var showActivity by rememberSaveable(state.session?.scope) { mutableStateOf(false) }
    var showNotebook by rememberSaveable(state.session?.scope) { mutableStateOf(false) }
    var showRequests by rememberSaveable(state.session?.scope) { mutableStateOf(false) }
    var showUploads by rememberSaveable(state.session?.scope) { mutableStateOf(false) }
    BackHandler(showLibrary || showActivity || showNotebook || showRequests || showUploads) {
        if (showUploads) showUploads = false
        else if (showRequests) showRequests = false
        else if (showNotebook) showNotebook = false
        else if (showActivity) showActivity = false
        else {
            showLibrary = false
            showDashboard = true
        }
    }
    var showEbooks by rememberSaveable(state.session?.scope) { mutableStateOf(false) }
    var signOut by remember { mutableStateOf(false) }
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val appearanceModel: AppearanceViewModel = viewModel(key = "appearance-${state.session?.scope}")
    val appearance by appearanceModel.state.collectAsStateWithLifecycle()
    var showAppearance by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    var showServers by remember { mutableStateOf(false) }
    LaunchedEffect(state.session?.scope) {
        showPlayer = false
        showAppearance = false
        showServers = false
        pendingEbook = null
    }
    val selectedTab =
        if (showLibrary) 4
        else if (showDashboard) 0 else if (showEbooks) 2 else if (state.offline) 3 else 1
    val selectTab: (Int) -> Unit = { tab ->
        showLibrary = tab == 4
        showActivity = false
        showNotebook = false
        showRequests = false
        showUploads = false
        showDashboard = tab == 0
        showEbooks = tab == 2
        if (tab == 1 || tab == 3) model.setOffline(tab == 3)
    }
    LaunchedEffect(widgetTab, state.session?.scope) {
        if (widgetTab != null && state.session != null) {
            selectTab(widgetTab)
            consumeWidget()
        }
    }
    val rail =
        useNavigationRail(androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp)
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(state.browserUrl) {
        state.browserUrl?.let { url ->
            try {
                context.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url),
                    )
                )
                model.browserOpened()
            } catch (_: android.content.ActivityNotFoundException) {
                model.browserFailed()
            }
        }
    }
    OrbitTheme(appearance) {
        AmbientBackground(Modifier.fillMaxSize()) {
            if (state.session == null || state.addingServer)
                SignIn(
                    state.busy,
                    state.error,
                    { server, username, password ->
                        model.login(server, username, password, controller)
                    },
                    if (state.session != null) ({ model.addServer(false) }) else null,
                    options = state.loginOptions,
                    optionsAddress = state.optionsAddress,
                    onOptions = model::loadLoginOptions,
                    onMagic = { address, token -> model.magicLogin(address, token, controller) },
                    onProvider = { address, provider ->
                        model.startOidc(address, provider, controller)
                    },
                )
            else
                AdaptiveAppScaffold(
                    selectedTab = selectedTab,
                    onTab = selectTab,
                    topBar = {
                        if (
                            !showDashboard &&
                                !showLibrary &&
                                !showActivity &&
                                !showNotebook &&
                                !showRequests &&
                                !showUploads
                        )
                            TopAppBar(
                                colors =
                                    TopAppBarDefaults.topAppBarColors(
                                        containerColor = Color.Transparent
                                    ),
                                title = {
                                    Column {
                                        Text(
                                            if (showEbooks) "Reading"
                                            else if (state.offline) "Downloads" else "Listening",
                                            style = MaterialTheme.typography.headlineSmall,
                                        )
                                        Text(
                                            if (showEbooks) "Books & comics"
                                            else if (state.offline) "Your offline library"
                                            else "Your audiobook library",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                actions = {
                                    if (!showEbooks)
                                        IconButton(onClick = { model.browse() }) {
                                            Icon(
                                                Icons.Rounded.Refresh,
                                                "Refresh",
                                                Modifier.size(20.dp),
                                            )
                                        }
                                    IconButton(onClick = { showAppearance = true }) {
                                        Icon(
                                            Icons.Rounded.Tune,
                                            "Appearance and account",
                                            Modifier.size(20.dp),
                                        )
                                    }
                                },
                            )
                    },
                    bottomBar = {
                        Column(Modifier.navigationBarsPadding()) {
                            current?.let { book ->
                                MiniPlayer(app, state.session!!, book, controller) {
                                    showPlayer = true
                                }
                            }
                            if (!rail) OrbitTabBar(selectedTab, selectTab)
                        }
                    },
                ) { padding ->
                    Crossfade(
                        targetState =
                            if (showUploads) "uploads"
                            else if (showRequests) "requests"
                            else if (showNotebook) "notebook"
                            else if (showActivity) "activity"
                            else if (showLibrary) "library"
                            else if (showDashboard) "home" else if (showEbooks) "read" else "audio",
                        animationSpec = tween(160),
                        label = "Screen transition",
                    ) { screen ->
                        if (screen == "uploads")
                            UploadsScreen(app, state.session!!, Modifier.padding(padding)) {
                                showUploads = false
                            }
                        else if (screen == "requests")
                            BookRequestsScreen(state.session!!, Modifier.padding(padding)) {
                                showRequests = false
                            }
                        else if (screen == "notebook")
                            NotebookScreen(
                                state.session!!,
                                Modifier.padding(padding),
                                onBack = { showNotebook = false },
                                onAudio = model::select,
                            )
                        else if (screen == "activity")
                            ReadingActivityScreen(state.session!!, Modifier.padding(padding)) {
                                showActivity = false
                            }
                        else if (screen == "library")
                            LibraryBrowser(
                                app,
                                state.session!!,
                                Modifier.padding(padding),
                                onRead = { id ->
                                    pendingEbook = id
                                    showLibrary = false
                                    showDashboard = false
                                    showEbooks = true
                                },
                                onListen = model::select,
                                onActivity = { showActivity = true },
                            )
                        else if (screen == "home")
                            Dashboard(
                                app,
                                state.session!!,
                                Modifier.padding(padding),
                                opening = state.busy,
                                openError = state.error,
                                onAppearance = { showAppearance = true },
                                onActivity = { showActivity = true },
                                onServers = { showServers = true },
                                onNotebook = { showNotebook = true },
                                onRequests = { showRequests = true },
                                onUploads = { showUploads = true },
                                onLibrary = {
                                    showDashboard = false
                                    showLibrary = true
                                },
                                onListen = model::select,
                                onRead = { id ->
                                    pendingEbook = id
                                    showDashboard = false
                                    showEbooks = true
                                },
                            )
                        else if (screen == "read")
                            EbookLibrary(
                                app,
                                state.session!!,
                                Modifier.padding(padding),
                                pendingEbook,
                                { pendingEbook = null },
                            )
                        else
                            AudioCatalog(
                                app,
                                state,
                                catalog,
                                work,
                                Modifier.padding(padding),
                                message,
                                model::search,
                                model::select,
                                model::sort,
                                model::readFilter,
                            )
                    }
                }
            if (showAppearance)
                AppearanceSheet(
                    appearance,
                    appearanceModel,
                    onClose = { showAppearance = false },
                    onPlaybackTools = {
                        showAppearance = false
                        showTools = true
                    },
                    onServers = {
                        showAppearance = false
                        showServers = true
                    },
                    onSignOut = {
                        showAppearance = false
                        signOut = true
                    },
                )
            if (showTools && state.session != null)
                PlaybackToolsDialog(
                    state.session!!,
                    {
                        showTools = false
                        controller?.sendCustomCommand(
                            SessionCommand(RELOAD_LISTENING, Bundle.EMPTY),
                            Bundle.EMPTY,
                        )
                    },
                    {
                        openBook(controller, it)
                        showPlayer = true
                    },
                )
            if (showServers && !state.addingServer)
                ServerConnections(
                    state.connections,
                    state.session?.scope,
                    state.busy,
                    state.error,
                    onSwitch = { model.switchServer(it, controller) },
                    onAdd = {
                        showServers = false
                        model.addServer(true)
                    },
                    onClose = { showServers = false },
                )
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
                        openBook(controller, book.book.id)
                        model.closeDetail()
                        showPlayer = true
                    },
                )
            }
            if (showPlayer && current != null && controller != null)
                ModalBottomSheet(
                    modifier = Modifier.fillMaxHeight(),
                    contentWindowInsets = { WindowInsets.safeDrawing },
                    dragHandle = null,
                    onDismissRequest = { showPlayer = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                ) {
                    PlayerSheet(current!!, controller, app, onClose = { showPlayer = false })
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
                    title = { Text("Disconnect this server?") },
                    text = {
                        Text(
                            "Only this connection will be signed out. Downloads stay on this device and are available when you connect this account again."
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                model.logout(controller)
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
private fun SignIn(
    busy: Boolean,
    error: String?,
    login: (String, String, String) -> Unit,
    onCancel: (() -> Unit)? = null,
    onMagic: (String, String) -> Unit = { _, _ -> },
    options: LoginOptions? = null,
    optionsAddress: String? = null,
    onOptions: (String) -> Unit = {},
    onProvider: (String, OidcProvider) -> Unit = { _, _ -> },
) {
    var server by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var magic by remember { mutableStateOf(false) }
    var magicValue by remember { mutableStateOf("") }
    val currentOptions = options.takeIf { optionsAddress == server.trim() }
    BackHandler(onCancel != null && !busy) { onCancel?.invoke() }
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
            if (onCancel != null)
                TextButton(onClick = onCancel, enabled = !busy) { Text("Back to your server") }
            Text(
                if (onCancel != null) "Connect another server" else "A home for\nyour books.",
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
            OutlinedButton(
                onClick = { onOptions(server) },
                enabled = !busy && server.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Load sign-in options")
            }
            TextButton(onClick = { magic = true }, enabled = !busy && server.isNotBlank()) {
                Text("Use a magic link")
            }
            currentOptions
                ?.oidcProviders
                ?.filter { it.enabled }
                ?.forEach { provider ->
                    Button(
                        onClick = {
                            password = ""
                            onProvider(server, provider)
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Continue with ${provider.displayName}")
                    }
                }
            if (currentOptions?.passwordLoginEnabled != false) {
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
            }
            error?.let { ErrorNotice(it) }
            if (currentOptions?.passwordLoginEnabled != false) {
                Button(
                    onClick = {
                        val supplied = password
                        password = ""
                        login(server, username, supplied)
                    },
                    enabled =
                        !busy &&
                            server.isNotBlank() &&
                            username.isNotBlank() &&
                            password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Text(if (busy) "Connecting…" else "Connect to library")
                }
            }
            Text(
                "An independent, open-source BookOrbit client. Provider sign-in opens your browser.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (magic)
        AlertDialog(
            onDismissRequest = {
                magic = false
                magicValue = ""
            },
            title = { Text("Magic-link sign-in") },
            text = {
                OutlinedTextField(
                    magicValue,
                    { magicValue = it.take(4096) },
                    label = { Text("Magic link or token") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = magicValue.isNotBlank(),
                    onClick = {
                        val value = magicValue
                        magicValue = ""
                        magic = false
                        onMagic(server, value)
                    },
                ) {
                    Text("Sign in")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        magicValue = ""
                        magic = false
                    }
                ) {
                    Text("Cancel")
                }
            },
        )
}

@Composable
fun ErrorNotice(message: String) {
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
    ebook: Boolean = false,
    onColor: ((Color) -> Unit)? = null,
) {
    var loaded by remember(session.scope, bookId, version) { mutableStateOf(false) }
    var aspect by
        remember(session.scope, bookId, version) { mutableFloatStateOf(if (ebook) 2f / 3f else 1f) }
    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (loaded) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded)
            Icon(
                if (ebook) Icons.Rounded.AutoStories else Icons.Rounded.Headphones,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        AsyncImage(
            model =
                ImageRequest.Builder(app)
                    .data("${session.server}/api/v1/books/$bookId/thumbnail")
                    .memoryCacheKey("${session.scope}/$bookId/$version")
                    .allowHardware(onColor == null)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .build(),
            onSuccess = { result ->
                loaded = true
                val drawable = result.result.drawable
                if (drawable.intrinsicHeight > 0 && drawable.intrinsicWidth > 0)
                    aspect = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight
                if (onColor != null) {
                    val bitmap =
                        (result.result.drawable as? android.graphics.drawable.BitmapDrawable)
                            ?.bitmap
                    if (bitmap != null) {
                        var r = 0L
                        var g = 0L
                        var b = 0L
                        var count = 0
                        for (x in
                            0 until
                                bitmap.width step
                                (bitmap.width / 12).coerceAtLeast(1)) for (y in
                            0 until bitmap.height step (bitmap.height / 12).coerceAtLeast(1)) {
                            val pixel = bitmap.getPixel(x, y)
                            val red = android.graphics.Color.red(pixel)
                            val green = android.graphics.Color.green(pixel)
                            val blue = android.graphics.Color.blue(pixel)
                            if (maxOf(red, green, blue) > 35 && minOf(red, green, blue) < 220) {
                                r += red
                                g += green
                                b += blue
                                count++
                            }
                        }
                        if (count > 0)
                            onColor(
                                Color((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
                            )
                    }
                }
            },
            onError = { loaded = false },
            imageLoader = app.coverLoader,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier =
                Modifier.size(
                        minOf(maxWidth, maxHeight * aspect),
                        minOf(maxWidth / aspect, maxHeight),
                    )
                    .shadow(4.dp, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp)),
        )
    }
}

fun openBook(controller: MediaController?, bookId: Int, resolution: String? = null) {
    controller?.sendCustomCommand(
        SessionCommand(OPEN_BOOK, Bundle.EMPTY),
        Bundle().apply {
            putInt("bookId", bookId)
            putString("resolution", resolution)
        },
    )
}

fun duration(value: Long): String {
    val seconds = (value / 1000).coerceAtLeast(0)
    return if (seconds >= 3600)
        "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}

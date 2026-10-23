package app.bookorbit.community.reader

import android.content.ClipData
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.*
import app.bookorbit.community.ui.*
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

class PageReaderActivity : ComponentActivity() {
    private val model: PageReaderViewModel by viewModels()
    private val notesModel: ReadingNotesViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val owner = intent.getStringExtra("scope").orEmpty()
        val fileId = intent.getIntExtra("fileId", 0)
        model.load(owner, fileId, intent.getStringExtra("noteId"))
        notesModel.load(owner, fileId)
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val notes by notesModel.notes.collectAsStateWithLifecycle()
            val noteError by notesModel.error.collectAsStateWithLifecycle()
            var controlsVisible by remember { mutableStateOf(false) }
            val snackbar = remember { SnackbarHostState() }
            var showNotes by remember { mutableStateOf(false) }
            var showSync by remember { mutableStateOf(false) }
            var jump by remember { mutableStateOf(false) }
            var marking by remember(state.page) { mutableStateOf(false) }
            var selecting by remember(state.page) { mutableStateOf(false) }
            val clipboard = LocalClipboard.current
            val copyScope = rememberCoroutineScope()
            var draft by remember { mutableStateOf<ReadingNote?>(null) }
            var scale by remember(state.page) { mutableFloatStateOf(1f) }
            var x by remember(state.page) { mutableFloatStateOf(0f) }
            var y by remember(state.page) { mutableFloatStateOf(0f) }
            var viewport by remember { mutableStateOf(IntSize.Zero) }
            ReaderSystemBars(window, controlsVisible, light = false)
            val touchExploration = rememberTouchExploration()
            LaunchedEffect(controlsVisible, touchExploration) {
                if (touchExploration) controlsVisible = true
                else if (controlsVisible) {
                    delay(6000)
                    controlsVisible = false
                }
            }
            LaunchedEffect(state.notice) {
                state.notice?.let {
                    snackbar.showSnackbar(it)
                    model.clearNotice(it)
                }
            }
            BackHandler {
                if (marking || selecting) {
                    marking = false
                    selecting = false
                } else finish()
            }
            val zoomState = rememberTransformableState { zoom, pan, _ ->
                scale = (scale * zoom).coerceIn(1f, 4f)
                x =
                    (x + pan.x).coerceIn(
                        -viewport.width * (scale - 1) / 2,
                        viewport.width * (scale - 1) / 2,
                    )
                y =
                    (y + pan.y).coerceIn(
                        -viewport.height * (scale - 1) / 2,
                        viewport.height * (scale - 1) / 2,
                    )
            }
            MaterialTheme(colorScheme = darkColorScheme()) {
                Scaffold(
                    contentWindowInsets = WindowInsets(0),
                    snackbarHost = { SnackbarHost(snackbar) },
                    topBar = {
                        if (controlsVisible)
                            Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                                TopAppBar(
                                    title = {
                                        Text(state.book?.title ?: "Opening document…", maxLines = 1)
                                    },
                                    navigationIcon = {
                                        TextButton(onClick = { finish() }) { Text("Close") }
                                    },
                                )
                                FlowRow(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    TextButton(
                                        onClick = {
                                            state.book?.let {
                                                draft =
                                                    ReadingNote(
                                                        revision = it.file.revision,
                                                        locator =
                                                            wireJson.encodeToString(
                                                                PageLocation(state.page)
                                                            ),
                                                        title = "Page ${state.page+1}",
                                                    )
                                            }
                                        },
                                        enabled = !state.busy,
                                    ) {
                                        Text("Bookmark")
                                    }
                                    TextButton(onClick = { showNotes = true }) { Text("Notes") }
                                    TextButton(
                                        onClick = { showSync = true },
                                        enabled = state.count > 0,
                                    ) {
                                        Text("Sync")
                                    }
                                    TextButton(
                                        onClick = {
                                            scale = 1f
                                            x = 0f
                                            y = 0f
                                        },
                                        enabled = !marking && !selecting,
                                    ) {
                                        Text("Fit")
                                    }
                                    TextButton(
                                        onClick = { scale = (scale + 0.5f).coerceAtMost(4f) },
                                        enabled = !marking && !selecting,
                                    ) {
                                        Text("Zoom +")
                                    }
                                }
                                if (state.book?.file?.format?.lowercase() == "pdf")
                                    Row {
                                        TextButton(
                                            onClick = {
                                                controlsVisible = false
                                                marking = !marking
                                                selecting = false
                                                model.clearSelection()
                                                scale = 1f
                                                x = 0f
                                                y = 0f
                                            },
                                            enabled = !state.busy && state.bitmap != null,
                                        ) {
                                            Text(
                                                if (marking) "Cancel area highlight"
                                                else "Highlight area"
                                            )
                                        }
                                        if (Build.VERSION.SDK_INT >= 35)
                                            TextButton(
                                                onClick = {
                                                    controlsVisible = false
                                                    selecting = !selecting
                                                    marking = false
                                                    model.clearSelection()
                                                    scale = 1f
                                                    x = 0f
                                                    y = 0f
                                                },
                                                enabled = !state.busy && state.bitmap != null,
                                            ) {
                                                Text(
                                                    if (selecting) "Cancel text selection"
                                                    else "Select text"
                                                )
                                            }
                                    }
                            }
                        if (marking || selecting)
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .displayCutoutPadding(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    onClick = {
                                        marking = false
                                        selecting = false
                                    }
                                ) {
                                    Text("Cancel selection")
                                }
                                Text(
                                    if (marking) "Drag over the page"
                                    else "Tap a word or drag a passage",
                                    Modifier.weight(1f),
                                )
                            }
                    },
                    bottomBar = {
                        if (controlsVisible)
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .navigationBarsPadding(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                TextButton(
                                    onClick = { model.go(state.page - 1) },
                                    enabled = !state.busy && state.page > 0,
                                ) {
                                    Text("Previous page")
                                }
                                TextButton(onClick = { jump = true }, enabled = state.count > 0) {
                                    Text("${state.page+1} / ${state.count}")
                                }
                                TextButton(
                                    onClick = { model.go(state.page + 1) },
                                    enabled = !state.busy && state.page + 1 < state.count,
                                ) {
                                    Text("Next page")
                                }
                            }
                    },
                ) { padding ->
                    Column(
                        Modifier.fillMaxSize().consumeWindowInsets(padding).displayCutoutPadding()
                    ) {
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        state.error?.let {
                            Text(
                                it,
                                Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Box(
                            Modifier.weight(1f)
                                .fillMaxWidth()
                                .clipToBounds()
                                .onSizeChanged { viewport = it }
                                .transformable(
                                    zoomState,
                                    canPan = { scale > 1f },
                                    enabled = !marking && !selecting,
                                )
                                .pointerInput(state.page, marking, selecting) {
                                    if (!marking && !selecting)
                                        detectTapGestures { point ->
                                            when (readerTap(point.x, size.width)) {
                                                ReaderTap.PREVIOUS -> {
                                                    controlsVisible = false
                                                    model.go(state.page - 1)
                                                }
                                                ReaderTap.NEXT -> {
                                                    controlsVisible = false
                                                    model.go(state.page + 1)
                                                }
                                                ReaderTap.CONTROLS ->
                                                    controlsVisible = !controlsVisible
                                            }
                                        }
                                }
                                .pointerInput(state.page, marking, selecting, scale > 1f) {
                                    if (!marking && !selecting && scale <= 1f) {
                                        var distance = 0f
                                        detectHorizontalDragGestures(
                                            onDragStart = { distance = 0f },
                                            onDragCancel = { distance = 0f },
                                            onDragEnd = {
                                                if (abs(distance) > 48.dp.toPx()) {
                                                    controlsVisible = false
                                                    model.go(
                                                        state.page + if (distance < 0) 1 else -1
                                                    )
                                                }
                                            },
                                        ) { _, amount ->
                                            distance += amount
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            state.bitmap?.let {
                                Image(
                                    it.asImageBitmap(),
                                    "Page ${state.page+1}",
                                    Modifier.fillMaxSize().graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        translationX = x
                                        translationY = y
                                    },
                                    contentScale = ContentScale.Fit,
                                )
                                if (state.book?.file?.format?.lowercase() == "pdf")
                                    PdfHighlightOverlay(
                                        state.pageWidth,
                                        state.pageHeight,
                                        marking,
                                        notes
                                            .filter { note ->
                                                note.revision == state.book?.file?.revision &&
                                                    note.highlight
                                            }
                                            .mapNotNull { note ->
                                                note.pdf?.takeIf { position ->
                                                    position.page == state.page
                                                }
                                            },
                                        Modifier.fillMaxSize().graphicsLayer {
                                            scaleX = scale
                                            scaleY = scale
                                            translationX = x
                                            translationY = y
                                        },
                                    ) { rect ->
                                        marking = false
                                        state.book?.let { book ->
                                            draft =
                                                ReadingNote(
                                                    revision = book.file.revision,
                                                    locator =
                                                        wireJson.encodeToString(
                                                            PageLocation(state.page)
                                                        ),
                                                    title = "Page ${state.page + 1} area",
                                                    highlight = true,
                                                    pdf =
                                                        AnnotationPdf(
                                                            state.page,
                                                            rect,
                                                            listOf(rect),
                                                        ),
                                                )
                                        }
                                    }
                                if (selecting)
                                    PdfTextSelectionOverlay(
                                        state.pageWidth,
                                        state.pageHeight,
                                        Modifier.fillMaxSize(),
                                    ) { ax, ay, bx, by ->
                                        selecting = false
                                        model.selectText(state.page, ax, ay, bx, by)
                                    }
                            }
                        }
                    }
                }
                state.selection?.let { selection ->
                    var copied by remember(selection) { mutableStateOf(false) }
                    AlertDialog(
                        onDismissRequest = model::clearSelection,
                        title = { Text("Selected text") },
                        text = {
                            SelectionContainer {
                                Text(
                                    selection.text,
                                    Modifier.heightIn(max = 260.dp)
                                        .verticalScroll(rememberScrollState()),
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    state.book?.let { book ->
                                        draft =
                                            ReadingNote(
                                                revision = book.file.revision,
                                                locator =
                                                    wireJson.encodeToString(
                                                        PageLocation(state.page)
                                                    ),
                                                title = "Page ${state.page + 1} passage",
                                                quote = selection.text,
                                                highlight = true,
                                                pdf = selection.position,
                                            )
                                    }
                                    model.clearSelection()
                                }
                            ) {
                                Text("Highlight selected text")
                            }
                        },
                        dismissButton = {
                            Row {
                                TextButton(
                                    onClick = {
                                        copyScope.launch {
                                            clipboard.setClipEntry(
                                                ClipEntry(
                                                    ClipData.newPlainText(
                                                        "Selected PDF text",
                                                        selection.text,
                                                    )
                                                )
                                            )
                                            copied = true
                                        }
                                    }
                                ) {
                                    Text(if (copied) "Copied" else "Copy")
                                }
                                TextButton(onClick = model::clearSelection) { Text("Close") }
                            }
                        },
                    )
                }
                draft?.let {
                    ReadingNoteEditor(it, { draft = null }) { value ->
                        notesModel.save(value)
                        draft = null
                    }
                }
                if (showNotes)
                    ReadingNotesPanel(
                        notes,
                        state.book?.file?.revision.orEmpty(),
                        noteError,
                        { showNotes = false },
                        { item ->
                            runCatching {
                                    wireJson.decodeFromString<PageLocation>(item.locator).page
                                }
                                .getOrNull()
                                ?.let(model::go)
                        },
                        notesModel::save,
                        notesModel::delete,
                        owner = intent.getStringExtra("scope").orEmpty(),
                        fileId = intent.getIntExtra("fileId", 0),
                        onReload = {
                            notesModel.load(
                                intent.getStringExtra("scope").orEmpty(),
                                intent.getIntExtra("fileId", 0),
                            )
                        },
                    )
                if (showSync)
                    ReadingSyncDialog(
                        application as OrbitApplication,
                        owner,
                        fileId,
                        EbookProgressWrite(
                            (state.page + 1) * 100.0 / state.count.coerceAtLeast(1),
                            state.page + 1,
                        ),
                        { showSync = false },
                    ) { remote ->
                        model.go(
                            ((remote.pageNumber?.minus(1))
                                    ?: ((remote.percentage / 100) * state.count).toInt())
                                .coerceIn(0, state.count - 1)
                        )
                    }
                if (jump) {
                    var page by remember { mutableStateOf((state.page + 1).toString()) }
                    AlertDialog(
                        onDismissRequest = { jump = false },
                        title = { Text("Go to page") },
                        text = {
                            OutlinedTextField(
                                page,
                                { page = it.filter(Char::isDigit).take(6) },
                                label = { Text("Page number") },
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    page.toIntOrNull()?.let { model.go(it - 1) }
                                    jump = false
                                },
                                enabled = (page.toIntOrNull() ?: 0) in 1..state.count,
                            ) {
                                Text("Go")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { jump = false }) { Text("Cancel") }
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (
            (application as OrbitApplication).sessions.read()?.scope !=
                intent.getStringExtra("scope")
        )
            finish()
    }
}

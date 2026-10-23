@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package app.bookorbit.community.reader

import android.os.Bundle
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.R
import app.bookorbit.community.data.EbookProgressWrite
import app.bookorbit.community.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.ReadError

private fun ReadingPreferences.readium() =
    EpubPreferences(
        fontSize = fontSize,
        scroll = scroll,
        theme =
            when (theme) {
                "dark" -> Theme.DARK
                "sepia" -> Theme.SEPIA
                else -> Theme.LIGHT
            },
    )

private fun contents(links: List<Link>, depth: Int = 0): List<Link> =
    if (depth > 20) emptyList()
    else links.take(2000).flatMap { listOf(it) + contents(it.children, depth + 1) }.take(2000)

class EpubReaderActivity : FragmentActivity(), EpubNavigatorFragment.Listener {
    private val model: EpubReaderViewModel by viewModels()
    private val notesModel: ReadingNotesViewModel by viewModels()
    private var noteDraft by mutableStateOf<ReadingNote?>(null)
    private var controlsVisible by mutableStateOf(false)
    private val snackbar = SnackbarHostState()
    private lateinit var readAloud: ReadAloudController
    private var speechLocator: Locator? = null
    private var showSpeech by mutableStateOf(false)
    private var sharedPosition by mutableStateOf<EbookProgressWrite?>(null)

    private fun mark(highlight: Boolean) {
        lifecycleScope.launch {
            val book = model.state.value.book ?: return@launch
            val selection = if (highlight) navigator?.currentSelection() else null
            if (highlight && selection == null) {
                snackbar.showSnackbar(
                    "Select some text first, then choose Highlight.",
                    duration = SnackbarDuration.Short,
                )
                return@launch
            }
            var locator = selection?.locator ?: navigator?.currentLocator?.value ?: return@launch
            if (highlight) {
                val range = navigator?.evaluateJavascript(EPUB_SELECTION_RANGE)
                val json = locator.toJSON()
                runCatching { JSONObject(range.orEmpty()) }
                    .getOrNull()
                    ?.let {
                        json.getJSONObject("locations").put("domRange", it)
                        locator = Locator.fromJSON(json) ?: locator
                    }
            }
            noteDraft =
                ReadingNote(
                    revision = book.file.revision,
                    locator = locator.toJSON().toString(),
                    title = if (highlight) "Highlighted passage" else "Bookmark",
                    quote = locator.text.highlight.orEmpty().take(10000),
                    highlight = highlight,
                    cfi =
                        if (
                            !highlight ||
                                locator.toJSON().getJSONObject("locations").has("domRange")
                        )
                            model.cfi(locator)
                        else null,
                )
        }
    }

    private var navigator by mutableStateOf<EpubNavigatorFragment?>(null)

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        supportFragmentManager.fragmentFactory = EpubNavigatorFragment.createDummyFactory()
        super.onCreate(savedInstanceState)
        supportFragmentManager
            .beginTransaction()
            .apply { supportFragmentManager.fragments.forEach { remove(it) } }
            .commitNow()
        enableEdgeToEdge()
        model.load(
            intent.getStringExtra("scope") ?: "",
            intent.getIntExtra("fileId", 0),
            intent.getStringExtra("noteId"),
        )
        notesModel.load(intent.getStringExtra("scope") ?: "", intent.getIntExtra("fileId", 0))
        readAloud =
            ReadAloudController(this, intent.getStringExtra("scope").orEmpty()) {
                sentence,
                before,
                after ->
                speechLocator?.let { base ->
                    val locator =
                        base.copy(
                            locations = Locator.Locations(),
                            text =
                                Locator.Text(before = before, highlight = sentence, after = after),
                        )
                    lifecycleScope.launch {
                        navigator?.applyDecorations(
                            listOf(
                                Decoration(
                                    "read-aloud",
                                    locator,
                                    Decoration.Style.Highlight(
                                        android.graphics.Color.rgb(245, 209, 101)
                                    ),
                                )
                            ),
                            "orbit-read-aloud",
                        )
                        navigator?.go(locator)
                    }
                }
            }
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            var showContents by remember { mutableStateOf(false) }
            var showPreferences by remember { mutableStateOf(false) }
            val prefs = state.preferences
            val notes by notesModel.notes.collectAsStateWithLifecycle()
            val notesError by notesModel.error.collectAsStateWithLifecycle()
            var showNotes by remember { mutableStateOf(false) }
            var showSync by remember { mutableStateOf(false) }
            val colors =
                when (prefs.theme) {
                    "dark" -> darkColorScheme()
                    "sepia" ->
                        lightColorScheme(
                            background = Color(0xFFF4ECD8),
                            surface = Color(0xFFF4ECD8),
                        )
                    else -> lightColorScheme()
                }
            ReaderSystemBars(window, controlsVisible, prefs.theme != "dark")
            val touchExploration = rememberTouchExploration()
            LaunchedEffect(controlsVisible, touchExploration) {
                if (touchExploration) controlsVisible = true
                else if (controlsVisible) {
                    delay(6000)
                    controlsVisible = false
                }
            }
            BackHandler { finish() }
            MaterialTheme(colorScheme = colors) {
                Scaffold(
                    contentWindowInsets = WindowInsets(0),
                    snackbarHost = { SnackbarHost(snackbar) },
                    topBar = {
                        if (controlsVisible)
                            Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                                TopAppBar(
                                    title = {
                                        Text(state.book?.title ?: "Opening ebook…", maxLines = 1)
                                    },
                                    navigationIcon = {
                                        IconButton(onClick = { finish() }) {
                                            Icon(
                                                Icons.AutoMirrored.Rounded.ArrowBack,
                                                "Close reader",
                                            )
                                        }
                                    },
                                    actions = {
                                        IconButton(
                                            onClick = {
                                                val book = state.book ?: return@IconButton
                                                val current = navigator ?: return@IconButton
                                                lifecycleScope.launch {
                                                    val location =
                                                        current.firstVisibleElementLocator()
                                                            ?: current.currentLocator.value
                                                    if (
                                                        speechLocator?.href != location.href ||
                                                            readAloud.state.value.count == 0
                                                    ) {
                                                        speechLocator = location
                                                        readAloud.load(
                                                            EbookStorage(
                                                                    this@EpubReaderActivity,
                                                                    intent
                                                                        .getStringExtra("scope")
                                                                        .orEmpty(),
                                                                )
                                                                .content(book.file),
                                                            location.href.toString(),
                                                            current.currentLocator.value.locations
                                                                .progression
                                                                ?: location.locations.progression
                                                                ?: 0.0,
                                                            location.text.highlight,
                                                        )
                                                    }
                                                    showSpeech = true
                                                }
                                            },
                                            enabled = navigator != null,
                                        ) {
                                            Icon(Icons.Rounded.VolumeUp, "Read aloud")
                                        }
                                        TextButton(
                                            onClick = { showContents = true },
                                            enabled = navigator != null,
                                        ) {
                                            Text("Contents")
                                        }
                                        TextButton(
                                            onClick = { showPreferences = true },
                                            enabled = navigator != null,
                                        ) {
                                            Text("Aa")
                                        }
                                    },
                                )
                                FlowRow(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    TextButton(
                                        onClick = { mark(false) },
                                        enabled = navigator != null,
                                    ) {
                                        Text("Bookmark")
                                    }
                                    TextButton(
                                        onClick = { mark(true) },
                                        enabled = navigator != null,
                                    ) {
                                        Text("Highlight")
                                    }
                                    TextButton(
                                        onClick = { showNotes = true },
                                        enabled = navigator != null,
                                    ) {
                                        Text("Notes")
                                    }
                                    TextButton(
                                        onClick = {
                                            lifecycleScope.launch {
                                                val current = navigator ?: return@launch
                                                val location =
                                                    current.firstVisibleElementLocator()
                                                        ?: current.currentLocator.value
                                                sharedPosition =
                                                    EbookProgressWrite(
                                                        ((current.currentLocator.value.locations
                                                                .totalProgression ?: 0.0) * 100)
                                                            .coerceIn(0.0, 100.0),
                                                        cfi = model.cfi(location),
                                                    )
                                                showSync = true
                                            }
                                        },
                                        enabled = navigator != null,
                                    ) {
                                        Text("Sync")
                                    }
                                }
                            }
                    },
                    bottomBar = {
                        val current = navigator
                        if (controlsVisible && current != null) {
                            val locator by current.currentLocator.collectAsStateWithLifecycle()
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .navigationBarsPadding(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                TextButton(onClick = { current.goBackward() }) {
                                    Text("Previous page")
                                }
                                Text(
                                    "${((locator.locations.totalProgression ?: 0.0) * 100).toInt()}%",
                                    Modifier.padding(16.dp),
                                )
                                TextButton(onClick = { current.goForward() }) { Text("Next page") }
                            }
                        }
                    },
                ) { padding ->
                    Column(
                        Modifier.fillMaxSize().consumeWindowInsets(padding).displayCutoutPadding()
                    ) {
                        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        state.error?.let {
                            Text(
                                it,
                                Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (state.publication != null)
                            AndroidView(
                                factory = { context ->
                                    FrameLayout(context).apply { id = R.id.epub_navigator }
                                },
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                update = { container ->
                                    container.post {
                                        if (
                                            navigator == null &&
                                                !isFinishing &&
                                                !isDestroyed &&
                                                !supportFragmentManager.isStateSaved
                                        )
                                            install(container, state)
                                    }
                                },
                            )
                    }
                }
                LaunchedEffect(notes, navigator) {
                    val book = state.book ?: return@LaunchedEffect
                    val decorations =
                        notes
                            .filter { it.highlight && it.revision == book.file.revision }
                            .mapNotNull { item ->
                                runCatching { Locator.fromJSON(JSONObject(item.locator)) }
                                    .getOrNull()
                                    ?.let {
                                        Decoration(
                                            id = item.id,
                                            locator = it,
                                            style =
                                                Decoration.Style.Highlight(
                                                    tint = android.graphics.Color.YELLOW
                                                ),
                                        )
                                    }
                            }
                    navigator?.applyDecorations(decorations, "orbit-notes")
                }
                if (showSpeech) ReadAloudDialog(readAloud) { showSpeech = false }
                noteDraft?.let { draft ->
                    ReadingNoteEditor(draft, { noteDraft = null }) {
                        notesModel.save(it)
                        noteDraft = null
                        navigator?.clearSelection()
                    }
                }
                if (showNotes)
                    ReadingNotesPanel(
                        notes,
                        state.book?.file?.revision.orEmpty(),
                        notesError,
                        { showNotes = false },
                        { item ->
                            runCatching { Locator.fromJSON(JSONObject(item.locator)) }
                                .getOrNull()
                                ?.let { navigator?.go(it) }
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
                        intent.getStringExtra("scope").orEmpty(),
                        intent.getIntExtra("fileId", 0),
                        sharedPosition ?: EbookProgressWrite(0.0),
                        { showSync = false },
                    ) { server ->
                        lifecycleScope.launch {
                            val exact = server.cfi?.let { model.locator(it) }
                            val target =
                                exact
                                    ?: state.publication?.locateProgression(
                                        (server.percentage / 100).coerceIn(0.0, 1.0)
                                    )
                            target?.let { navigator?.go(it) }
                            if (server.cfi != null && exact == null)
                                model.message(
                                    "This server location could not be resolved. Opened its approximate percentage."
                                )
                        }
                    }
                LaunchedEffect(prefs, navigator) { navigator?.submitPreferences(prefs.readium()) }
                if (showContents)
                    AlertDialog(
                        onDismissRequest = { showContents = false },
                        title = { Text("Contents") },
                        text = {
                            val publication = state.publication
                            val entries =
                                remember(publication) {
                                    publication
                                        ?.let {
                                            contents(it.tableOfContents.ifEmpty { it.readingOrder })
                                        }
                                        .orEmpty()
                                }
                            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                                items(entries.withIndex().toList(), key = { it.index }) { item ->
                                    TextButton(
                                        onClick = {
                                            navigator?.go(item.value)
                                            showContents = false
                                        }
                                    ) {
                                        Text(item.value.title ?: "Section ${item.index + 1}")
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showContents = false }) { Text("Close") }
                        },
                    )
                if (showPreferences)
                    AlertDialog(
                        onDismissRequest = { showPreferences = false },
                        title = { Text("Reading preferences") },
                        text = {
                            Column {
                                Text("Text size · ${(prefs.fontSize * 100).toInt()}%")
                                Row {
                                    TextButton(
                                        onClick = {
                                            model.preferences(
                                                prefs.copy(
                                                    fontSize =
                                                        (prefs.fontSize - 0.1).coerceAtLeast(0.7)
                                                )
                                            )
                                        }
                                    ) {
                                        Text("Smaller text")
                                    }
                                    TextButton(
                                        onClick = {
                                            model.preferences(
                                                prefs.copy(
                                                    fontSize =
                                                        (prefs.fontSize + 0.1).coerceAtMost(2.0)
                                                )
                                            )
                                        }
                                    ) {
                                        Text("Larger text")
                                    }
                                }
                                listOf("light", "sepia", "dark").forEach { theme ->
                                    FilterChip(
                                        selected = prefs.theme == theme,
                                        onClick = { model.preferences(prefs.copy(theme = theme)) },
                                        label = { Text(theme.replaceFirstChar { it.uppercase() }) },
                                    )
                                }
                                Row {
                                    Checkbox(
                                        checked = prefs.scroll,
                                        onCheckedChange = {
                                            model.preferences(prefs.copy(scroll = it))
                                        },
                                    )
                                    Text("Scroll pages", Modifier.padding(top = 12.dp))
                                }
                                Text(
                                    "Reading position and preferences are saved on this device.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showPreferences = false }) { Text("Done") }
                        },
                    )
            }
        }
    }

    private fun install(container: FrameLayout, state: EpubReaderState) {
        val publication = state.publication ?: return
        val factory =
            EpubNavigatorFactory(publication)
                .createFragmentFactory(
                    initialLocator = state.initialLocator,
                    initialPreferences = state.preferences.readium(),
                    listener = this,
                    configuration =
                        EpubNavigatorFragment.Configuration(
                            selectionActionModeCallback =
                                object : ActionMode.Callback {
                                    override fun onCreateActionMode(
                                        mode: ActionMode,
                                        menu: Menu,
                                    ): Boolean {
                                        menu.add(0, 1001, 0, "Highlight")
                                        return true
                                    }

                                    override fun onPrepareActionMode(mode: ActionMode, menu: Menu) =
                                        false

                                    override fun onActionItemClicked(
                                        mode: ActionMode,
                                        item: MenuItem,
                                    ): Boolean {
                                        if (item.itemId != 1001) return false
                                        mark(true)
                                        return true
                                    }

                                    override fun onDestroyActionMode(mode: ActionMode) {}
                                }
                        ),
                )
        supportFragmentManager.fragmentFactory = factory
        val fragment =
            factory.instantiate(classLoader, EpubNavigatorFragment::class.java.name)
                as EpubNavigatorFragment
        supportFragmentManager
            .beginTransaction()
            .replace(container.id, fragment, "epub")
            .commitNow()
        navigator = fragment
        fragment.addInputListener(
            object : InputListener {
                override fun onTap(event: TapEvent): Boolean {
                    lifecycleScope.launch {
                        if (fragment.currentSelection() != null) {
                            controlsVisible = true
                            return@launch
                        }
                        when (readerTap(event.point.x, fragment.publicationView.width)) {
                            ReaderTap.PREVIOUS -> {
                                controlsVisible = false
                                fragment.goBackward()
                            }
                            ReaderTap.NEXT -> {
                                controlsVisible = false
                                fragment.goForward()
                            }
                            ReaderTap.CONTROLS -> controlsVisible = !controlsVisible
                        }
                    }
                    return true
                }
            }
        )
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                fragment.currentLocator.collect { model.saveLocation(it) }
            }
        }
    }

    override fun onPause() {
        if (::readAloud.isInitialized) readAloud.pause()
        navigator?.currentLocator?.value?.let(model::saveLocation)
        super.onPause()
    }

    override fun onDestroy() {
        if (::readAloud.isInitialized) readAloud.close()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        val app = application as OrbitApplication
        if (app.sessions.read()?.scope != intent.getStringExtra("scope")) finish()
    }

    override fun onExternalLinkActivated(url: AbsoluteUrl) {
        model.message("External links stay closed while reading.")
    }

    override fun onResourceLoadFailed(href: Url, error: ReadError) {
        model.message("A part of this ebook could not be displayed.")
    }
}

private const val EPUB_SELECTION_RANGE =
    """
(() => {
    const selection = window.getSelection();
    if (!selection || !selection.rangeCount || selection.isCollapsed) return null;
    const range = selection.getRangeAt(0);
    function point(node, offset) {
        if (node.nodeType !== Node.TEXT_NODE) return null;
        const parent = node.parentElement;
        const path = parent.getAttribute('data-orbit-cfi');
        if (!path || !/^([/][0-9]+)+$/.test(path)) return null;
        const nodes = Array.from(parent.childNodes).filter(n => n.nodeType === Node.TEXT_NODE);
        return {cssSelector: '[data-orbit-cfi="' + path + '"]', textNodeIndex: nodes.indexOf(node), charOffset: offset};
    }
    const start = point(range.startContainer, range.startOffset);
    const end = point(range.endContainer, range.endOffset);
    return start && end ? {start, end} : null;
})()
"""

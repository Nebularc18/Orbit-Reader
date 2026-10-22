package app.bookorbit.community.ui

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.work.WorkManager
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.AudioManifest
import app.bookorbit.community.data.playbackSpeeds
import app.bookorbit.community.playback.AudioTimeline
import app.bookorbit.community.playback.RELOAD_LISTENING
import app.bookorbit.community.playback.SLEEP_TIMER
import kotlinx.coroutines.delay

private data class BookmarkDraft(
    val assetIndex: Int,
    val positionMs: Long,
    val title: String,
    val id: String? = null,
)

@Composable
fun PlayerSheet(
    book: AudioManifest,
    controller: MediaController,
    app: OrbitApplication,
    onClose: () -> Unit = {},
) {
    SystemBarAppearance(MaterialTheme.colorScheme.surface.luminance() > .5f)
    var position by remember { mutableLongStateOf(0) }
    var playing by remember { mutableStateOf(controller.isPlaying) }
    var speed by remember { mutableFloatStateOf(controller.playbackParameters.speed) }
    var assetIndex by remember {
        mutableIntStateOf(controller.currentMediaItemIndex.coerceAtLeast(0))
    }
    var showChapters by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showTimer by remember { mutableStateOf(false) }
    val timeline = remember(book) { AudioTimeline(book) }
    var bookScrub by remember(book.book.id) { mutableStateOf<Float?>(null) }
    val sleep by app.sleepMinutes.collectAsStateWithLifecycle()
    val sleepAtChapterEnd by app.sleepAtChapterEnd.collectAsStateWithLifecycle()
    val session = remember { app.sessions.read()!! }
    val model: PlayerViewModel =
        viewModel(
            key = "listening-${session.scope}-${book.book.id}-${book.revision}",
            factory = viewModelFactory { initializer { PlayerViewModel(app, session.scope, book) } },
        )
    val listening by model.state.collectAsStateWithLifecycle()
    var showBookmarks by remember(book) { mutableStateOf(false) }
    var showSkips by remember { mutableStateOf(false) }
    var draft by remember(book) { mutableStateOf<BookmarkDraft?>(null) }
    var backSeconds by remember { mutableIntStateOf((controller.seekBackIncrement / 1000).toInt()) }
    var forwardSeconds by remember {
        mutableIntStateOf((controller.seekForwardIncrement / 1000).toInt())
    }
    val syncWork by
        remember(book.book.id) {
                WorkManager.getInstance(app)
                    .getWorkInfosForUniqueWorkFlow("progress-${session.scope}-${book.book.id}")
            }
            .collectAsStateWithLifecycle(initialValue = emptyList())
    LaunchedEffect(controller) {
        while (true) {
            position = controller.currentPosition.coerceAtLeast(0)
            playing = controller.isPlaying
            speed = controller.playbackParameters.speed
            backSeconds = (controller.seekBackIncrement / 1000).toInt()
            forwardSeconds = (controller.seekForwardIncrement / 1000).toInt()
            assetIndex = controller.currentMediaItemIndex.coerceAtLeast(0)
            delay(500)
        }
    }
    val elapsed = timeline.elapsed(assetIndex, position)
    val chapterIndex = timeline.chapterIndex(bookScrub?.toLong() ?: elapsed)
    val chapter = book.chapters.getOrNull(chapterIndex)
    var chapterScrub by
        remember(book.book.id, chapterIndex, if (chapter == null) assetIndex else -1) {
            mutableStateOf<Float?>(null)
        }
    val assetDuration =
        book.assets.getOrNull(assetIndex)?.durationMs ?: controller.duration.coerceAtLeast(1)
    val chapterStart = chapter?.startMs ?: timeline.elapsed(assetIndex, 0)
    val chapterDuration = chapter?.let { (it.endMs - it.startMs).coerceAtLeast(0) } ?: assetDuration
    val shownElapsed =
        bookScrub?.toLong() ?: chapterScrub?.let { chapterStart + it.toLong() } ?: elapsed
    fun seekBook(milliseconds: Long) {
        val target = timeline.seek(milliseconds) ?: return
        controller.seekTo(target.assetIndex, target.positionMs)
        assetIndex = target.assetIndex
        position = target.positionMs
    }
    var coverColor by remember(book.book.id) { mutableStateOf<Color?>(null) }
    var more by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val glow = coverColor ?: MaterialTheme.colorScheme.primary
        val coverSize = (maxHeight * .28f).coerceIn(168.dp, 260.dp)
        Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surface))
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
            drawRect(
                Brush.radialGradient(
                    listOf(glow.copy(alpha = .55f), glow.copy(alpha = .12f), Color.Transparent),
                    center = Offset(size.width * .5f, size.height * .27f),
                    radius = size.width * .95f,
                )
            )
        }
        Column(
            Modifier.fillMaxWidth()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 12.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CircleAction(Icons.Rounded.KeyboardArrowDown, "Close player", onClose)
                Text(
                    "${duration(remainingAudioTime(timeline.durationMs, shownElapsed))} left",
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    CircleAction(Icons.Rounded.MoreHoriz, "More playback options", { more = true })
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        DropdownMenuItem(
                            text = { Text("Skip intervals") },
                            onClick = {
                                more = false
                                showSkips = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Bookmarks") },
                            onClick = {
                                more = false
                                showBookmarks = true
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            BookCover(
                app,
                session,
                book.book.id,
                book.revision,
                Modifier.size(coverSize),
                onColor = { coverColor = it },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                book.book.title,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                if (book.book.narrators.isNotEmpty())
                    "Narrated by ${book.book.narrators.joinToString(", ")}"
                else book.book.authors.joinToString(", "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                chapter?.title ?: "Part ${assetIndex + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PlaybackTimeline(
                label =
                    if (chapter != null) "Chapter ${chapterIndex + 1} of ${book.chapters.size}"
                    else "Part ${assetIndex + 1} of ${book.assets.size}",
                elapsedMs = (shownElapsed - chapterStart).coerceIn(0, chapterDuration),
                durationMs = chapterDuration,
                onScrub = { chapterScrub = it },
                onSeek = {
                    chapterScrub?.let {
                        if (timeline.hasDurations) seekBook(chapterStart + it.toLong())
                        else controller.seekTo(it.toLong())
                    }
                    chapterScrub = null
                },
                enabled = timeline.hasDurations || chapter == null,
            )
            PlaybackTimeline(
                label = "Whole book",
                elapsedMs = shownElapsed.coerceIn(0, timeline.durationMs),
                durationMs = timeline.durationMs,
                onScrub = { bookScrub = it },
                onSeek = {
                    bookScrub?.let { seekBook(it.toLong()) }
                    bookScrub = null
                },
                showRemaining = listening.showRemainingTime,
                onTimeToggle = model::toggleTimeDisplay,
                enabled = timeline.hasDurations,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        val previous =
                            if (elapsed - chapterStart > 3000) chapterStart
                            else book.chapters.getOrNull(chapterIndex - 1)?.startMs ?: 0L
                        seekBook(previous)
                    },
                    enabled = timeline.hasDurations,
                ) {
                    Icon(Icons.Rounded.SkipPrevious, "Previous chapter", Modifier.size(24.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircleAction(
                        Icons.Rounded.Replay,
                        "Back $backSeconds seconds",
                        { controller.seekBack() },
                    )
                    Text(
                        "${backSeconds}s",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                    )
                }
                FilledIconButton(
                    onClick = {
                        if (controller.playbackState == Player.STATE_ENDED) {
                            controller.seekTo(0, 0)
                            controller.play()
                        } else if (controller.playWhenReady) controller.pause()
                        else controller.play()
                    },
                    modifier = Modifier.size(68.dp),
                ) {
                    Icon(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (playing) "Pause" else "Play",
                        Modifier.size(30.dp),
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircleAction(
                        Icons.Rounded.FastForward,
                        "Forward $forwardSeconds seconds",
                        { controller.seekForward() },
                    )
                    Text(
                        "${forwardSeconds}s",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                    )
                }
                IconButton(
                    onClick = {
                        book.chapters.getOrNull(chapterIndex + 1)?.let { seekBook(it.startMs) }
                    },
                    enabled =
                        timeline.hasDurations &&
                            chapterIndex >= 0 &&
                            chapterIndex + 1 < book.chapters.size,
                ) {
                    Icon(Icons.Rounded.SkipNext, "Next chapter", Modifier.size(24.dp))
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { showSpeed = true },
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    modifier = Modifier.height(44.dp),
                ) {
                    Text("${speed}×", style = MaterialTheme.typography.labelMedium)
                }
                PlayerTool(
                    Icons.AutoMirrored.Rounded.FormatListBulleted,
                    "Chapters",
                    { showChapters = true },
                )
                PlayerTool(Icons.Rounded.BookmarkBorder, "Bookmarks", { showBookmarks = true })
                PlayerTool(
                    Icons.Rounded.Bedtime,
                    if (sleepAtChapterEnd) "Chapter end"
                    else if (sleep > 0) "$sleep min" else "Sleep timer",
                    { showTimer = true },
                    active = sleepAtChapterEnd || sleep > 0,
                )
            }
            listening.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            syncWork.firstOrNull()?.outputData?.getString("error")?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    if (showSpeed)
        AlertDialog(
            onDismissRequest = { showSpeed = false },
            title = { Text("Playback speed") },
            text = {
                Column {
                    playbackSpeeds.forEach { value ->
                        TextButton(
                            onClick = {
                                controller.setPlaybackSpeed(value)
                                speed = value
                                showSpeed = false
                            }
                        ) {
                            Text("${value}×")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSpeed = false }) { Text("Close") } },
        )
    if (showSkips)
        SkipIntervalsDialog(
            initial = listening.intervals,
            busy = listening.busy,
            error = listening.error,
            onDismiss = { showSkips = false },
            onSave = { value ->
                model.setIntervals(value) {
                    controller.sendCustomCommand(
                        SessionCommand(RELOAD_LISTENING, Bundle.EMPTY),
                        Bundle.EMPTY,
                    )
                    showSkips = false
                }
            },
        )
    if (showBookmarks && draft == null)
        BookmarksDialog(
            book = book,
            bookmarks = listening.bookmarks,
            busy = listening.busy,
            error = listening.error,
            onDismiss = { showBookmarks = false },
            onAdd = {
                val index = controller.currentMediaItemIndex.coerceAtLeast(0)
                val at = controller.currentPosition.coerceAtLeast(0)
                draft = BookmarkDraft(index, at, "Part ${index + 1} · ${duration(at)}")
            },
            onSeek = { bookmark ->
                bookmark.target(book)?.let { index ->
                    controller.seekTo(index, bookmark.positionMs)
                }
                showBookmarks = false
            },
            onRename = { bookmark ->
                draft = BookmarkDraft(0, bookmark.positionMs, bookmark.title, bookmark.id)
            },
            onDelete = model::deleteBookmark,
        )
    draft?.let { edit ->
        BookmarkNameDialog(
            initial = edit.title,
            onDismiss = { draft = null },
            onSave = { name ->
                if (edit.id == null) model.addBookmark(edit.assetIndex, edit.positionMs, name)
                else model.renameBookmark(edit.id, name)
                draft = null
            },
        )
    }
    if (showTimer)
        AlertDialog(
            onDismissRequest = { showTimer = false },
            title = { Text("Sleep timer") },
            text = {
                Column {
                    TextButton(
                        enabled =
                            timeline.chapterEnd(
                                controller.currentMediaItemIndex,
                                controller.currentPosition,
                            ) != null,
                        onClick = {
                            controller.sendCustomCommand(
                                SessionCommand(SLEEP_TIMER, Bundle.EMPTY),
                                Bundle().apply { putBoolean("endOfChapter", true) },
                            )
                            showTimer = false
                        },
                    ) {
                        Text("End of chapter")
                    }
                    listOf(0, 15, 30, 45, 60).forEach { minutes ->
                        TextButton(
                            onClick = {
                                controller.sendCustomCommand(
                                    SessionCommand(SLEEP_TIMER, Bundle.EMPTY),
                                    Bundle().apply { putInt("minutes", minutes) },
                                )
                                showTimer = false
                            }
                        ) {
                            Text(if (minutes == 0) "Off" else "$minutes minutes")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTimer = false }) { Text("Close") } },
        )
    if (showChapters)
        AlertDialog(
            onDismissRequest = { showChapters = false },
            title = { Text("Chapters") },
            text = {
                val currentChapterIndex = timeline.chapterIndex(elapsed)
                val currentChapterId = book.chapters.getOrNull(currentChapterIndex)?.id
                val chapterList =
                    rememberLazyListState(
                        initialFirstVisibleItemIndex = currentChapterIndex.coerceAtLeast(0)
                    )
                LazyColumn(Modifier.fillMaxWidth(), state = chapterList) {
                    items(book.chapters, key = { it.id }) { chapter ->
                        val current = chapter.id == currentChapterId
                        TextButton(
                            modifier =
                                Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics {
                                    selected = current
                                },
                            shape = MaterialTheme.shapes.medium,
                            colors =
                                ButtonDefaults.textButtonColors(
                                    containerColor =
                                        if (current) MaterialTheme.colorScheme.secondaryContainer
                                        else Color.Transparent,
                                    contentColor =
                                        if (current) MaterialTheme.colorScheme.onSecondaryContainer
                                        else MaterialTheme.colorScheme.primary,
                                ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            onClick = {
                                val index =
                                    book.assets.indexOfFirst { it.assetId == chapter.assetId }
                                if (index >= 0) controller.seekTo(index, chapter.assetOffsetMs)
                                showChapters = false
                            },
                        ) {
                            Text(
                                "${chapter.title} · ${duration(chapter.startMs)}",
                                modifier = Modifier.weight(1f),
                                fontWeight =
                                    if (current) FontWeight.SemiBold else FontWeight.Normal,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                            )
                            if (current)
                                Icon(
                                    Icons.Rounded.PlayArrow,
                                    "Current chapter",
                                    Modifier.padding(start = 8.dp).size(20.dp),
                                )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showChapters = false }) { Text("Close") } },
        )
}

@Composable
private fun PlayerTool(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            Icon(
                icon,
                label,
                Modifier.size(22.dp),
                tint =
                    if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color =
                if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

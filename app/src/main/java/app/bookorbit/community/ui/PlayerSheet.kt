package app.bookorbit.community.ui

import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.work.WorkManager
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.AudioManifest
import app.bookorbit.community.playback.AudioTimeline
import app.bookorbit.community.playback.SLEEP_TIMER
import kotlinx.coroutines.delay

@Composable
fun PlayerSheet(book: AudioManifest, controller: MediaController, app: OrbitApplication) {
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
    val session = remember { app.sessions.read()!! }
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
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp)
            .padding(bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BookCover(app, session, book.book.id, book.revision, Modifier.size(176.dp, 184.dp))
        Text(
            book.book.title,
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Serif,
        )
        Text(
            book.book.authors.joinToString(", "),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            chapter?.title ?: "Part ${assetIndex + 1}",
            style = MaterialTheme.typography.labelLarge,
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
            markers = timeline.chapterFractions,
            enabled = timeline.hasDurations,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { controller.seekBack() }) {
                Icon(Icons.Rounded.Replay, "Back 15 seconds", Modifier.size(32.dp))
            }
            FilledIconButton(
                onClick = {
                    if (controller.playbackState == Player.STATE_ENDED) {
                        controller.seekTo(0, 0)
                        controller.play()
                    } else if (controller.playWhenReady) controller.pause() else controller.play()
                },
                modifier = Modifier.size(72.dp),
            ) {
                Icon(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (playing) "Pause" else "Play",
                    Modifier.size(36.dp),
                )
            }
            IconButton(onClick = { controller.seekForward() }) {
                Icon(Icons.Rounded.Forward30, "Forward 30 seconds", Modifier.size(32.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = { showSpeed = true }) { Text("${speed}×") }
            TextButton(onClick = { showChapters = true }) { Text("Chapters") }
            TextButton(onClick = { showTimer = true }) {
                Text(if (sleep > 0) "Sleep: $sleep min" else "Sleep timer")
            }
        }
        syncWork.firstOrNull()?.outputData?.getString("error")?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (showSpeed)
        AlertDialog(
            onDismissRequest = { showSpeed = false },
            title = { Text("Playback speed") },
            text = {
                Column {
                    listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f).forEach { value ->
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
    if (showTimer)
        AlertDialog(
            onDismissRequest = { showTimer = false },
            title = { Text("Sleep timer") },
            text = {
                Column {
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
                LazyColumn {
                    items(book.chapters, key = { it.id }) { chapter ->
                        TextButton(
                            onClick = {
                                val index =
                                    book.assets.indexOfFirst { it.assetId == chapter.assetId }
                                if (index >= 0) controller.seekTo(index, chapter.assetOffsetMs)
                                showChapters = false
                            }
                        ) {
                            Text("${chapter.title} · ${duration(chapter.startMs)}")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showChapters = false }) { Text("Close") } },
        )
}

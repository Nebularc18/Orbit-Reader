package app.bookorbit.community.ui

import android.Manifest
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.AudioManifest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookSheet(
    book: AudioManifest,
    state: LibraryState,
    app: OrbitApplication,
    model: LibraryViewModel,
    controller: MediaController?,
    onPlay: () -> Unit,
) {
    val workManager = remember { WorkManager.getInstance(app) }
    val work by
        remember(book.book.id, state.session!!.scope) {
                workManager.getWorkInfosForUniqueWorkFlow(
                    "download-${state.session!!.scope}-${book.book.id}"
                )
            }
            .collectAsStateWithLifecycle(initialValue = emptyList())
    val job = work.firstOrNull { !it.state.isFinished } ?: work.firstOrNull()
    val downloading =
        job?.state in
            listOf(WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED)
    val complete = state.downloaded
    val downloadedBytes = job?.progress?.getLong("downloadedBytes", -1L)?.takeIf { it >= 0 }
    val totalBytes = job?.progress?.getLong("totalBytes", -1L)?.takeIf { it > 0 }
    val downloadFraction =
        if (downloadedBytes != null && totalBytes != null)
            (downloadedBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
        else null
    LaunchedEffect(job?.state) {
        if (job?.state == WorkInfo.State.SUCCEEDED) model.refreshDownloaded()
    }
    val current by app.currentManifest.collectAsStateWithLifecycle()
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var confirmDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = model::closeDetail) {
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 700.dp).padding(horizontal = 24.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    book.book.title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Serif,
                )
            }
            item {
                Text(
                    book.book.authors.joinToString(", "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (book.book.narrators.isNotEmpty())
                item {
                    Text(
                        "Narrated by ${book.book.narrators.joinToString(", ")}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            item {
                Text(
                    "${duration(book.totalDurationMs)} · ${book.assets.size} audio files · ${book.chapters.size} chapters",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            item {
                Button(
                    onClick = onPlay,
                    enabled = controller != null && (!state.offline || complete),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (complete) "Listen offline" else "Listen now")
                }
            }
            if (state.session!!.canDownload && !complete && !downloading)
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Download on Wi-Fi only", Modifier.padding(top = 12.dp))
                        Switch(state.wifiOnly, model::wifiOnly)
                    }
                    OutlinedButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= 33)
                                notificationPermission.launch(
                                    Manifest.permission.POST_NOTIFICATIONS
                                )
                            model.download()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (job?.state == WorkInfo.State.FAILED) "Retry download"
                            else if (
                                state.selectedBytes > 0 || job?.state == WorkInfo.State.CANCELLED
                            )
                                "Resume download"
                            else "Download audiobook"
                        )
                    }
                }
            if (downloading)
                item {
                    if (downloadFraction != null)
                        LinearProgressIndicator(
                            progress = { downloadFraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (downloadedBytes != null) {
                        Text(
                            (downloadFraction?.let { "${(it * 100).toInt()}% · " } ?: "") +
                                Formatter.formatShortFileSize(app, downloadedBytes) +
                                (totalBytes?.let { " of ${Formatter.formatShortFileSize(app, it)}" }
                                    ?: " downloaded"),
                            Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Text(
                        if (job?.state == WorkInfo.State.ENQUEUED) "Waiting for network or retry…"
                        else
                            "Downloading file ${job?.progress?.getInt("asset", 1)} of ${book.assets.size}",
                        Modifier.padding(top = 8.dp),
                    )
                    TextButton(
                        onClick = {
                            workManager.cancelUniqueWork(
                                "download-${state.session!!.scope}-${book.book.id}"
                            )
                        }
                    ) {
                        Text("Pause download")
                    }
                }
            if (job?.state == WorkInfo.State.FAILED)
                item {
                    Text(
                        job?.outputData?.getString("error") ?: "Download failed. Try again.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            if (complete || state.selectedBytes > 0 || state.offline)
                item {
                    Text(
                        if (complete) "Available offline" else "Download incomplete",
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "${Formatter.formatShortFileSize(app, state.selectedBytes)} on this device"
                    )
                    TextButton(
                        onClick = { confirmDelete = true },
                        enabled = current?.book?.id != book.book.id,
                    ) {
                        Text("Remove download")
                    }
                }
            if (book.chapters.isNotEmpty())
                item {
                    Text(
                        "Chapters",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            items(book.chapters, key = { it.id }) { chapter ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        chapter.title,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(duration(chapter.startMs), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    if (confirmDelete)
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove downloaded audio?") },
            text = {
                Text(
                    "Only audio on this device will be removed. Your server book and listening position will be kept."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.removeDownload()
                        confirmDelete = false
                    }
                ) {
                    Text("Remove")
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
}

package app.bookorbit.community.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkManager
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.*
import app.bookorbit.community.reader.queueReadingSync
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class ReadingSyncViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OrbitApplication
    val remote = MutableStateFlow<EbookProgress?>(null)
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    var workId by mutableStateOf<String?>(null)
        private set

    fun refresh(owner: String, fileId: Int) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                remote.value = withContext(Dispatchers.IO) { app.api.ebookProgress(fileId, owner) }
                message.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                remote.value = null
                message.value = "Cannot reach the server. Your local position is safe."
            } finally {
                busy.value = false
            }
        }
    }

    fun publish(owner: String, fileId: Int, value: EbookProgressWrite) {
        val baseline = remote.value ?: return
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                workId =
                    withContext(Dispatchers.IO) {
                        queueReadingSync(app, owner, fileId, baseline, value)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                message.value = "Could not queue progress. Try again."
            } finally {
                busy.value = false
            }
        }
    }
}

@Composable
fun ReadingSyncDialog(
    app: OrbitApplication,
    owner: String,
    fileId: Int,
    value: EbookProgressWrite,
    onDismiss: () -> Unit,
    onUseServer: (EbookProgress) -> Unit,
) {
    val model: ReadingSyncViewModel = viewModel(key = "reading-sync-$owner-$fileId")
    val remote by model.remote.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val jobs by
        remember(owner, fileId) {
                WorkManager.getInstance(app)
                    .getWorkInfosForUniqueWorkFlow("reading-sync-$owner-$fileId")
            }
            .collectAsStateWithLifecycle(initialValue = emptyList())
    val job = jobs.firstOrNull { it.id.toString() == model.workId }
    val pending = job != null && !job.state.isFinished
    LaunchedEffect(Unit) { model.refresh(owner, fileId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share reading progress") },
        text = {
            Column {
                Text("This device: ${"%.1f".format(value.percentage)}%")
                remote?.let { Text("Server: ${"%.1f".format(it.percentage)}%") }
                Text(
                    "Publishing replaces the server position. BookOrbit cannot lock ebook progress against simultaneous writes."
                )
                if (value.pageNumber == null)
                    Text(
                        if (value.cfi != null) "EPUB source location available."
                        else "Exact location unavailable. Sharing percentage only."
                    )
                if (busy || pending) LinearProgressIndicator()
                message?.let { Text(it) }
                job?.outputData?.getString("message")?.let { Text(it) }
                remote?.let { server ->
                    TextButton(
                        onClick = {
                            onUseServer(server)
                            onDismiss()
                        },
                        enabled = !busy && !pending,
                    ) {
                        Text("Use server position")
                    }
                    TextButton(
                        onClick = { model.publish(owner, fileId, value) },
                        enabled = !busy && !pending,
                    ) {
                        Text("Publish this position")
                    }
                }
                TextButton(
                    onClick = { model.refresh(owner, fileId) },
                    enabled = !busy && !pending,
                ) {
                    Text("Refresh server progress")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

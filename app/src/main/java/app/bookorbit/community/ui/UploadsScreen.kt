package app.bookorbit.community.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkManager
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.*
import app.bookorbit.community.upload.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class UploadsState(
    val capabilities: UploadCapabilities? = null,
    val jobs: List<UploadJob> = emptyList(),
    val error: String? = null,
)

class UploadsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OrbitApplication
    val owner = app.sessions.read()?.scope.orEmpty()
    private val storage = UploadStorage(app, owner)
    private val mutable = MutableStateFlow(UploadsState())
    val state = mutable.asStateFlow()

    private var polling: Job? = null

    fun observe() {
        refresh()
        polling?.cancel()
        polling =
            viewModelScope.launch {
                while (isActive) {
                    val jobs = withContext(Dispatchers.IO) { storage.all() }
                    if (app.sessions.read()?.scope == owner) mutable.update { it.copy(jobs = jobs) }
                    delay(1000)
                }
            }
    }

    fun stopObserving() {
        polling?.cancel()
        polling = null
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val caps = withContext(Dispatchers.IO) { app.api.uploadCapabilities(owner) }
                check(app.sessions.read()?.scope == owner)
                mutable.update { it.copy(capabilities = caps, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutable.update {
                    it.copy(
                        error =
                            "Uploads are unavailable. Check server support, your connection, and library upload permission."
                    )
                }
            }
        }
    }

    fun select(uri: Uri, libraryId: Int, folderId: Int?) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(app.sessions.read()?.scope == owner)
                    val (name, size) =
                        app.contentResolver
                            .query(
                                uri,
                                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                                null,
                                null,
                                null,
                            )!!
                            .use { cursor ->
                                check(cursor.moveToFirst())
                                cursor.getString(0) to cursor.getLong(1)
                            }
                    val target = UploadTarget(libraryId = libraryId, folderId = folderId)
                    validateUploadFile(
                        name,
                        size,
                        target,
                        mutable.value.capabilities ?: error("Refresh upload options first."),
                    )
                    require(storage.all().size < 30) {
                        "Remove an old upload task before adding another."
                    }
                    app.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                    val job =
                        UploadJob(
                            uri = uri.toString(),
                            filename = name,
                            size = size,
                            target = target,
                        )
                    storage.save(job)
                    enqueueUpload(app, owner, job.id)
                }
                mutable.update { it.copy(error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutable.update {
                    it.copy(
                        error =
                            "Could not add this file. Choose a supported file with a known size, allow lasting read access, and check the server's size limit. At most 30 tasks are kept per account."
                    )
                }
            }
        }
    }

    fun action(job: UploadJob, action: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(app.sessions.read()?.scope == owner)
                    val manager = WorkManager.getInstance(app)
                    if (action == "resume") enqueueUpload(app, owner, job.id)
                    else {
                        manager.cancelUniqueWork("upload-$owner-${job.id}").result.get()
                        if (action == "remove") {
                            storage.remove(job.id)
                            if (storage.all().none { it.uri == job.uri })
                                runCatching {
                                    app.contentResolver.releasePersistableUriPermission(
                                        Uri.parse(job.uri),
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                    )
                                }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutable.update { it.copy(error = "Could not update this upload task.") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UploadsScreen(
    app: OrbitApplication,
    account: Session,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    val model: UploadsViewModel = viewModel(key = "uploads-${account.scope}")
    DisposableEffect(model) {
        model.observe()
        onDispose { model.stopObserving() }
    }
    val state by model.state.collectAsStateWithLifecycle()
    val work by
        remember(account.scope) {
                WorkManager.getInstance(app).getWorkInfosByTagFlow("uploads-${account.scope}")
            }
            .collectAsStateWithLifecycle(emptyList())
    var libraryId by rememberSaveable(account.scope) { mutableStateOf<Int?>(null) }
    var folderId by rememberSaveable(account.scope) { mutableStateOf<Int?>(null) }
    var remove by remember { mutableStateOf<UploadJob?>(null) }
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { value -> libraryId?.let { model.select(value, it, folderId) } }
        }
    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            "Uploads",
            "Send a book to your server",
            back = onBack,
            refresh = model::refresh,
        )
        LazyColumn(
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { message -> item { LibraryErrorState(message, model::refresh) } }
            state.capabilities?.let { caps ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Uploads follow your Wi-Fi download preference. A private staged copy is kept until the server finishes importing."
                        )
                        if (!caps.canUploadToLibrary || caps.libraries.isEmpty())
                            Text("No libraries are available for upload with this account.")
                        else {
                            var choose by remember { mutableStateOf(false) }
                            val library = caps.libraries.firstOrNull { it.id == libraryId }
                            Box {
                                OutlinedButton(onClick = { choose = true }) {
                                    Text(library?.name ?: "Choose library")
                                }
                                DropdownMenu(choose, { choose = false }) {
                                    caps.libraries.forEach { value ->
                                        DropdownMenuItem(
                                            text = { Text(value.name) },
                                            onClick = {
                                                libraryId = value.id
                                                folderId = null
                                                choose = false
                                            },
                                        )
                                    }
                                }
                            }
                            if (library != null && library.folders.isNotEmpty()) {
                                var folderMenu by remember { mutableStateOf(false) }
                                Box {
                                    OutlinedButton(onClick = { folderMenu = true }) {
                                        Text(
                                            library.folders.firstOrNull { it.id == folderId }?.name
                                                ?: "Default folder"
                                        )
                                    }
                                    DropdownMenu(folderMenu, { folderMenu = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Default folder") },
                                            onClick = {
                                                folderId = null
                                                folderMenu = false
                                            },
                                        )
                                        library.folders.forEach { folder ->
                                            DropdownMenuItem(
                                                text = { Text(folder.name) },
                                                onClick = {
                                                    folderId = folder.id
                                                    folderMenu = false
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                            Text(
                                "Maximum file size: ${android.text.format.Formatter.formatShortFileSize(app, caps.maxFileSizeBytes)}"
                            )
                            Button(
                                onClick = { pick.launch(arrayOf("*/*")) },
                                enabled = library != null,
                            ) {
                                Text("Choose file to upload")
                            }
                        }
                    }
                }
            }
            items(state.jobs, key = { it.id }) { job ->
                val active =
                    work.any { !it.state.isFinished && it.tags.contains("upload-${job.id}") }
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(job.filename, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${job.phase.replaceFirstChar(Char::uppercase)} · ${android.text.format.Formatter.formatShortFileSize(app, job.bytes)} / ${android.text.format.Formatter.formatShortFileSize(app, job.size)}"
                        )
                        LinearProgressIndicator(
                            progress = {
                                (job.bytes.toDouble() / job.size).toFloat().coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        job.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (
                                job.phase !in listOf("completed", "failed", "expired", "cancelled")
                            ) {
                                TextButton(
                                    onClick = { model.action(job, "resume") },
                                    enabled = !active,
                                ) {
                                    Text("Resume / retry")
                                }
                                TextButton(
                                    onClick = { model.action(job, "pause") },
                                    enabled = active,
                                ) {
                                    Text("Pause")
                                }
                            }
                            TextButton(onClick = { remove = job }) { Text("Remove local task") }
                        }
                    }
                }
            }
        }
    }
    remove?.let { job ->
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text("Remove this local task?") },
            text = {
                Text(
                    "Its staged copy will be removed. Files already received or imported by the server are kept."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        remove = null
                        model.action(job, "remove")
                    }
                ) {
                    Text("Remove")
                }
            },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("Keep task") } },
        )
    }
}

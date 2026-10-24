package app.bookorbit.community.upload

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.*
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.R
import app.bookorbit.community.data.*
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withPermit

fun enqueueUpload(context: Context, owner: String, id: String) {
    val work =
        OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf("scope" to owner, "id" to id))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (PlaybackToolsStorage(context, owner).options().wifiOnly)
                            NetworkType.UNMETERED
                        else NetworkType.CONNECTED
                    )
                    .build()
            )
            .addTag("account-$owner")
            .addTag("uploads-$owner")
            .addTag("upload-$id")
            .build()
    WorkManager.getInstance(context)
        .enqueueUniqueWork("upload-$owner-$id", ExistingWorkPolicy.KEEP, work)
}

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as OrbitApplication
        val owner = inputData.getString("scope") ?: return Result.failure()
        val id = inputData.getString("id") ?: return Result.failure()
        val storage = UploadStorage(app, owner)
        var job = runCatching { storage.read(id) }.getOrNull() ?: return Result.failure()
        fun checkOwner() {
            check(app.sessions.read()?.scope == owner)
        }
        if (app.sessions.read()?.scope != owner)
            return if (app.sessions.hasConnection(owner)) Result.retry() else Result.failure()
        setForeground(foreground(job.filename))
        return app.downloadSlots.withPermit {
            withContext(Dispatchers.IO) {
                try {
                    checkOwner()
                    val caps = app.api.uploadCapabilities(owner)
                    ensureActive()
                    validateUploadFile(job.filename, job.size, job.target, caps)
                    val content = storage.content(id)
                    if (!content.isFile || content.length() != job.size) {
                        require(storage.availableBytes() > job.size + 16 * 1024 * 1024) {
                            "Not enough free space to stage this upload."
                        }
                        job = job.copy(phase = "copying", error = null)
                        check(storage.update(job))
                        val partial = File(content.path + ".part")
                        app.contentResolver.openInputStream(Uri.parse(job.uri))!!.use { input ->
                            partial.outputStream().use { output ->
                                val buffer = ByteArray(65536)
                                var total = 0L
                                while (true) {
                                    ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    require(total <= job.size)
                                    output.write(buffer, 0, count)
                                }
                                require(total == job.size) {
                                    "The selected file changed. Select it again."
                                }
                            }
                        }
                        check(partial.renameTo(content))
                    }
                    checkOwner()
                    ensureActive()
                    var remote =
                        job.remoteId?.let { app.api.uploadSession(it, owner) }
                            ?: app.api.createUpload(
                                UploadCreate(job.filename, job.size, job.id, job.target),
                                owner,
                            )
                    ensureActive()
                    require(remote.sizeBytes == job.size)
                    job = job.copy(remoteId = remote.id, error = null)
                    check(storage.update(job))
                    RandomAccessFile(content, "r").use { file ->
                        while (remote.status == "receiving" && remote.receivedBytes < job.size) {
                            ensureActive()
                            checkOwner()
                            require(
                                remote.receivedBytes in 0..job.size && remote.chunkSizeBytes > 0
                            )
                            val size =
                                minOf(
                                        remote.chunkSizeBytes.toLong(),
                                        4L * 1024 * 1024,
                                        job.size - remote.receivedBytes,
                                    )
                                    .toInt()
                            val bytes = ByteArray(size)
                            file.seek(remote.receivedBytes)
                            file.readFully(bytes)
                            val expected = remote.receivedBytes + size
                            remote =
                                app.api.uploadChunk(remote.id, remote.receivedBytes, bytes, owner)
                            require(
                                remote.receivedBytes == expected && remote.sizeBytes == job.size
                            )
                            ensureActive()
                            job = job.copy(phase = "uploading", bytes = remote.receivedBytes)
                            check(storage.update(job))
                            setProgress(workDataOf("bytes" to job.bytes, "size" to job.size))
                        }
                    }
                    if (remote.status == "receiving") {
                        checkOwner()
                        remote = app.api.finishUpload(remote.id, owner)
                    }
                    ensureActive()
                    job =
                        job.copy(phase = remote.status, bytes = remote.receivedBytes, error = null)
                    check(storage.update(job))
                    when (remote.status) {
                        "completed" -> {
                            content.delete()
                            Result.success()
                        }
                        "processing" -> Result.retry()
                        else -> Result.failure()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ensureActive()
                    storage.update(
                        job.copy(
                            error =
                                when (e) {
                                    is java.io.FileNotFoundException,
                                    is SecurityException ->
                                        "The selected file is unavailable. Remove the task and select it again."
                                    is ApiFailure -> e.message ?: "The server refused this upload."
                                    is java.io.IOException ->
                                        "The connection was interrupted. Resume to check the server before continuing."
                                    else ->
                                        "Could not prepare or finish this upload. Check the file and server limits, or select the file again."
                                }
                        )
                    )
                    if (
                        e is java.io.IOException &&
                            (e !is ApiFailure || e.status >= 500 || e.status == 409)
                    )
                        Result.retry()
                    else Result.failure()
                }
            }
        }
    }

    private fun foreground(title: String): ForegroundInfo {
        val manager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel("uploads", "Book uploads", NotificationManager.IMPORTANCE_LOW)
        )
        val notification =
            NotificationCompat.Builder(applicationContext, "uploads")
                .setSmallIcon(R.drawable.ic_orbit)
                .setContentTitle("Uploading book")
                .setContentText(title)
                .setOngoing(true)
                .build()
        return ForegroundInfo(
            id.hashCode(),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }
}

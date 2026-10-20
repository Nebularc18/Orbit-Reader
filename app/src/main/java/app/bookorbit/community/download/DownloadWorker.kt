package app.bookorbit.community.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.*
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.R
import app.bookorbit.community.data.*
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class DownloadWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        setForeground(notification(inputData.getInt("bookId", 0), "Waiting to download"))
        val app = applicationContext as OrbitApplication
        return app.downloadSlots.withPermit {
            app.downloadLock(inputData.getString("scope") ?: "", inputData.getInt("bookId", 0))
                .withLock { download() }
        }
    }

    private suspend fun download(): Result =
        withContext(Dispatchers.IO) {
            val app = applicationContext as OrbitApplication
            val accountScope = inputData.getString("scope") ?: return@withContext Result.failure()
            val bookId = inputData.getInt("bookId", 0)
            val session =
                app.sessions.read()?.takeIf { it.scope == accountScope }
                    ?: return@withContext Result.failure()
            if (!session.canDownload)
                return@withContext Result.failure(
                    workDataOf("error" to "Your account cannot download books.")
                )
            val storage = AudioStorage(app, accountScope)
            try {
                setForeground(notification(bookId, "Preparing audiobook"))
                val manifest = app.api.manifest(bookId, accountScope)
                storage.markDownload(manifest)
                val progress = DownloadProgress(manifest.assets)
                manifest.assets.forEachIndexed { index, asset ->
                    val file = storage.asset(bookId, asset)
                    val bytes =
                        if (file.isFile) file.length()
                        else java.io.File(file.path + ".part").length()
                    progress.update(index, bytes)
                }
                suspend fun report(index: Int) {
                    val percent = progress.fraction?.let { "${(it * 100).toInt()}% · " } ?: ""
                    setProgress(
                        workDataOf(
                            "asset" to index + 1,
                            "total" to manifest.assets.size,
                            "downloadedBytes" to progress.downloadedBytes,
                            "totalBytes" to (progress.totalBytes ?: -1L),
                        )
                    )
                    setForeground(
                        notification(bookId, "$percent${manifest.book.title}", progress.fraction)
                    )
                }
                for ((index, asset) in manifest.assets.withIndex()) {
                    if (isStopped || app.sessions.read()?.scope != accountScope)
                        return@withContext Result.failure()
                    val file = storage.asset(bookId, asset)
                    if (
                        file.isFile &&
                            file.length() > 0 &&
                            (asset.sizeBytes == null || file.length() == asset.sizeBytes)
                    ) {
                        progress.update(index, file.length(), file.length())
                        report(index)
                        continue
                    }
                    report(index)
                    var lastReport = 0L
                    val coroutineContext = currentCoroutineContext()
                    AudioTransfer()
                        .download(
                            file,
                            asset.sizeBytes,
                            request = { offset, validator ->
                                app.api.download(asset, accountScope, offset, validator)
                            },
                            checkActive = {
                                coroutineContext.ensureActive()
                                if (isStopped) throw IOException("Download cancelled")
                            },
                            progress = { bytes, total ->
                                progress.update(index, bytes, total)
                                if (SystemClock.elapsedRealtime() - lastReport >= 1000) {
                                    if (app.sessions.read()?.scope != accountScope)
                                        throw IOException("Download cancelled")
                                    lastReport = SystemClock.elapsedRealtime()
                                    report(index)
                                }
                            },
                        )
                    report(index)
                }
                Result.success()
            } catch (error: ApiFailure) {
                if ((error.status == 429 || error.status in 500..599) && runAttemptCount < 4)
                    Result.retry()
                else Result.failure(workDataOf("error" to error.message))
            } catch (_: IOException) {
                if (runAttemptCount < 4) Result.retry()
                else
                    Result.failure(
                        workDataOf(
                            "error" to "Download interrupted. Retry when your server is reachable."
                        )
                    )
            }
        }

    private fun notification(bookId: Int, text: String, fraction: Float? = null): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                "downloads",
                "Audiobook downloads",
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        val notification =
            NotificationCompat.Builder(applicationContext, "downloads")
                .setSmallIcon(R.drawable.ic_orbit)
                .setContentTitle("Orbit Reader")
                .setContentText(text)
                .setOngoing(true)
                .setProgress(100, fraction?.let { (it * 100).toInt() } ?: 0, fraction == null)
                .addAction(
                    0,
                    "Pause",
                    WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
                )
                .build()
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(
                bookId + 1000,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        else ForegroundInfo(bookId + 1000, notification)
    }
}

fun enqueueDownload(context: Context, session: Session, bookId: Int, wifiOnly: Boolean) {
    WorkManager.getInstance(context)
        .enqueueUniqueWork(
            "download-${session.scope}-$bookId",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf("scope" to session.scope, "bookId" to bookId))
                .addTag("account-${session.scope}")
                .addTag("downloads-${session.scope}")
                .addTag("book-$bookId")
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                        )
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .build(),
        )
}

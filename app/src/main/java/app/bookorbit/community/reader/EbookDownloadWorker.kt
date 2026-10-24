package app.bookorbit.community.reader

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
import app.bookorbit.community.data.ApiFailure
import app.bookorbit.community.data.PlaybackToolsStorage
import app.bookorbit.community.data.hasConnection
import app.bookorbit.community.download.OfflineLimitReached
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

fun enqueueEbook(
    context: Context,
    owner: String,
    fileId: Int,
    needsNetwork: Boolean = true,
): String {
    val work =
        OneTimeWorkRequestBuilder<EbookDownloadWorker>()
            .setInputData(workDataOf("scope" to owner, "fileId" to fileId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (!needsNetwork) NetworkType.NOT_REQUIRED
                        else if (PlaybackToolsStorage(context, owner).options().wifiOnly)
                            NetworkType.UNMETERED
                        else NetworkType.CONNECTED
                    )
                    .build()
            )
            .addTag("account-$owner")
            .addTag("ebooks-$owner")
            .build()
    val manager = WorkManager.getInstance(context)
    val name = "ebook-$owner-$fileId"
    manager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, work).result.get()
    val jobs = manager.getWorkInfosForUniqueWork(name).get()
    return (jobs.firstOrNull { !it.state.isFinished } ?: jobs.first { it.id == work.id })
        .id
        .toString()
}

class EbookDownloadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as OrbitApplication
        val owner = inputData.getString("scope") ?: return Result.failure()
        val fileId = inputData.getInt("fileId", 0)
        setForeground(notification(fileId, "Preparing ebook"))
        return app.downloadSlots.withPermit {
            app.downloadLock(owner, -fileId).withLock {
                withContext(Dispatchers.IO) { download(app, owner, fileId) }
            }
        }
    }

    private suspend fun download(app: OrbitApplication, owner: String, fileId: Int): Result {
        if (app.sessions.read()?.scope != owner)
            return if (app.sessions.hasConnection(owner)) Result.retry() else Result.failure()
        val storage = EbookStorage(app, owner)
        val book = storage.book(fileId) ?: return Result.failure()
        val target = storage.content(book.file)
        val partial = File(target.path + ".part")
        try {
            if (storage.ready(book.file)) return Result.success()
            app.offlineBudget.reserve(owner, "ebook-$owner-$fileId", book.file.sizeBytes ?: 0)
            if (book.file.format?.lowercase() in listOf("cbr", "cb7")) {
                val count = app.api.comicPages(fileId, owner)
                require(count in 1..20000) { "This comic has no pages or too many pages." }
                var total = 0L
                ZipOutputStream(partial.outputStream()).use { output ->
                    output.setLevel(0)
                    for (page in 0 until count) {
                        currentCoroutineContext().ensureActive()
                        app.api.comicPage(fileId, page, owner).use { response ->
                            val mime =
                                response.header("Content-Type").orEmpty().substringBefore(';')
                            val extension =
                                mapOf(
                                    "image/jpeg" to "jpg",
                                    "image/png" to "png",
                                    "image/webp" to "webp",
                                    "image/gif" to "gif",
                                    "image/avif" to "avif",
                                    "image/bmp" to "bmp",
                                )[mime]
                                    ?: throw IllegalArgumentException(
                                        "Unsupported comic image format."
                                    )
                            output.putNextEntry(ZipEntry("%06d.%s".format(page, extension)))
                            var bytes = 0L
                            response.body!!.byteStream().use { input ->
                                val buffer = ByteArray(65536)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val size = input.read(buffer)
                                    if (size < 0) break
                                    bytes += size
                                    total += size
                                    require(
                                        bytes <= 32L * 1024 * 1024 &&
                                            total <= EbookStorage.MAX_DOWNLOAD_BYTES
                                    ) {
                                        "Comic exceeds the supported size."
                                    }
                                    output.write(buffer, 0, size)
                                    if (bytes / (1024 * 1024) != (bytes - size) / (1024 * 1024))
                                        app.offlineBudget.check(owner)
                                }
                            }
                            output.closeEntry()
                        }
                        setProgress(
                            workDataOf(
                                "bytes" to total,
                                "total" to -1L,
                                "page" to page + 1,
                                "pages" to count,
                            )
                        )
                    }
                }
                EbookStorage.validateArchive(partial, epub = false)
            } else {
                app.api.ebookContent(fileId, owner).use { response ->
                    val body = response.body ?: throw IOException()
                    val length = body.contentLength()
                    val expected = book.file.sizeBytes?.takeIf { it > 0 }
                    require(
                        length <= EbookStorage.MAX_DOWNLOAD_BYTES &&
                            (expected ?: 0) <= EbookStorage.MAX_DOWNLOAD_BYTES
                    ) {
                        "This ebook exceeds the 512 MB limit."
                    }
                    var received = 0L
                    var lastReport = 0L
                    body.byteStream().use { source ->
                        partial.outputStream().use { sink ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = source.read(buffer)
                                if (count < 0) break
                                received += count
                                require(received <= EbookStorage.MAX_DOWNLOAD_BYTES) {
                                    "This ebook exceeds the 512 MB limit."
                                }
                                sink.write(buffer, 0, count)
                                if (received / (1024 * 1024) != (received - count) / (1024 * 1024))
                                    app.offlineBudget.check(owner)
                                if (SystemClock.elapsedRealtime() - lastReport > 700) {
                                    if (app.sessions.read()?.scope != owner) throw IOException()
                                    setProgress(
                                        workDataOf(
                                            "bytes" to received,
                                            "total" to (expected ?: length),
                                        )
                                    )
                                    lastReport = SystemClock.elapsedRealtime()
                                }
                            }
                        }
                    }
                    if (
                        received == 0L ||
                            (length >= 0 && length != received) ||
                            (expected != null && expected != received)
                    )
                        throw IOException("Incomplete ebook")
                }
                if (book.file.format?.lowercase() in listOf("fb2", "mobi")) {
                    require(partial.length() > 0)
                } else if (book.file.format?.lowercase() == "pdf") {
                    partial.inputStream().use {
                        require(
                            ByteArray(5)
                                .also { header -> java.io.DataInputStream(it).readFully(header) }
                                .toString(Charsets.US_ASCII) == "%PDF-"
                        ) {
                            "This file is not a PDF."
                        }
                    }
                } else
                    EbookStorage.validateArchive(
                        partial,
                        epub = book.file.format?.lowercase() != "cbz",
                    )
            }
            if (app.sessions.read()?.scope != owner)
                return if (app.sessions.hasConnection(owner)) Result.retry() else Result.failure()
            app.offlineBudget.check(owner)
            if (!partial.renameTo(target)) throw IOException()
            return Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (app.sessions.read()?.scope != owner && app.sessions.hasConnection(owner))
                return Result.retry()
            if (error is OfflineLimitReached)
                return Result.failure(workDataOf("error" to error.message))
            if (error is IOException && error !is ApiFailure && runAttemptCount < 3)
                return Result.retry()
            return Result.failure(
                workDataOf(
                    "error" to
                        if (error is IllegalArgumentException) error.message
                        else "Could not prepare this ebook. Check your connection and retry."
                )
            )
        } finally {
            app.offlineBudget.release("ebook-$owner-$fileId")
            partial.delete()
        }
    }

    private fun notification(fileId: Int, text: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("ebooks", "Ebook downloads", NotificationManager.IMPORTANCE_LOW)
        )
        val notice =
            NotificationCompat.Builder(applicationContext, "ebooks")
                .setSmallIcon(R.drawable.ic_orbit)
                .setContentTitle("Orbit Reader")
                .setContentText(text)
                .setOngoing(true)
                .build()
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(20000 + fileId, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(20000 + fileId, notice)
    }
}

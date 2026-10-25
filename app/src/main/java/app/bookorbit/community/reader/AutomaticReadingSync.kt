@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package app.bookorbit.community.reader

import android.content.Context
import androidx.work.*
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

fun matchesReadingPosition(remote: EbookProgress, value: EbookProgressWrite): Boolean =
    // BookOrbit stores percentages as PostgreSQL real, so compare at its precision.
    remote.percentage.toFloat() == value.percentage.toFloat() &&
        remote.cfi == value.cfi &&
        remote.pageNumber == value.pageNumber

suspend fun prepareAutomaticReadingSync(app: OrbitApplication, owner: String, file: EbookFile) {
    app.readingSyncLock(owner, file.id).withLock {
        check(app.sessions.read()?.scope == owner)
        val storage = EbookStorage(app, owner)
        val existing = storage.readingUpload(file.id)
        if (existing?.revision == file.revision && existing.baseline != null) return@withLock
        val baseline =
            try {
                app.api.ebookProgress(file.id, owner)
            } catch (_: IOException) {
                null
            }
        storage.prepareReadingUpload(file, baseline)
    }
}

fun enqueueAutomaticReadingSync(
    context: Context,
    owner: String,
    fileId: Int,
    flush: Boolean = true,
) {
    val upload = EbookStorage(context, owner).readingUpload(fileId)
    if (upload?.pending == null || upload.blocked) return
    WorkManager.getInstance(context)
        .enqueueUniqueWork(
            "automatic-reading-sync-$owner-$fileId",
            if (flush) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AutomaticReadingSyncWorker>()
                .setInputData(workDataOf("owner" to owner, "fileId" to fileId))
                .setInitialDelay(if (flush) 0 else 15, TimeUnit.SECONDS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .addTag("account-$owner")
                .build(),
        )
        .result
        .get()
}

class AutomaticReadingSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val app = applicationContext as OrbitApplication
            val owner = inputData.getString("owner") ?: return@withContext Result.failure()
            val fileId = inputData.getInt("fileId", 0)
            app.readingSyncLock(owner, fileId).withLock {
                if (app.sessions.read()?.scope != owner)
                    return@withLock if (app.sessions.hasConnection(owner)) Result.retry()
                    else Result.failure()
                syncAutomaticReadingProgress(app.api, EbookStorage(app, owner), owner, fileId)
            }
        }
}

internal fun syncAutomaticReadingProgress(
    api: OrbitApi,
    storage: EbookStorage,
    owner: String,
    fileId: Int,
): ListenableWorker.Result {
    if (api.sessions.read()?.scope != owner)
        return if (api.sessions.hasConnection(owner)) ListenableWorker.Result.retry()
        else ListenableWorker.Result.failure()
    val upload = storage.readingUpload(fileId) ?: return ListenableWorker.Result.success()
    val pending = upload.pending ?: return ListenableWorker.Result.success()
    if (upload.blocked || upload.baseline == null) return conflict(storage, fileId)
    return try {
        val book = storage.book(fileId) ?: return ListenableWorker.Result.failure()
        if (book.file.revision != upload.revision) return conflict(storage, fileId)
        val file = api.ebookDetail(book.bookId, owner).files.firstOrNull { it.id == fileId }
        if (file?.revision != upload.revision) return conflict(storage, fileId)
        val value =
            if (upload.locator != null && book.file.format?.lowercase() in setOf("epub", "kepub")) {
                val cfi =
                    runCatching {
                            Locator.fromJSON(JSONObject(upload.locator))?.let {
                                EpubCfi(storage.content(book.file)).fromLocator(it)
                            }
                        }
                        .getOrNull()
                pending.copy(cfi = cfi)
            } else pending
        val remote = api.ebookProgress(fileId, owner)
        // A previous response may have been lost after the server committed our write.
        if (matchesReadingPosition(remote, value)) {
            storage.acknowledgeReadingUpload(fileId, upload, remote)
            return if (storage.readingUpload(fileId)?.pending != null)
                ListenableWorker.Result.retry()
            else ListenableWorker.Result.success()
        }
        if (!sameReadingProgress(remote, upload.baseline)) return conflict(storage, fileId)
        api.saveEbookProgress(fileId, owner, value)
        val saved = api.ebookProgress(fileId, owner)
        if (!matchesReadingPosition(saved, value)) return conflict(storage, fileId)
        storage.acknowledgeReadingUpload(fileId, upload, saved)
        if (storage.readingUpload(fileId)?.pending != null) ListenableWorker.Result.retry()
        else ListenableWorker.Result.success()
    } catch (error: ApiFailure) {
        if (api.sessions.read()?.scope != owner)
            return if (api.sessions.hasConnection(owner)) ListenableWorker.Result.retry()
            else ListenableWorker.Result.failure()
        if (error.status >= 500 || error.status == 429) ListenableWorker.Result.retry()
        else conflict(storage, fileId)
    } catch (_: IOException) {
        ListenableWorker.Result.retry()
    }
}

private fun conflict(storage: EbookStorage, fileId: Int): ListenableWorker.Result {
    storage.blockReadingUpload(fileId)
    return ListenableWorker.Result.failure(
        workDataOf(
            "message" to
                "Automatic sync paused. Open Sync to review server progress. Your local position is safe."
        )
    )
}

package app.bookorbit.community.reader

import android.content.Context
import androidx.work.*
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.*
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

fun sameReadingProgress(a: EbookProgress, b: EbookProgress): Boolean = a == b

class ReadingSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val app = applicationContext as OrbitApplication
            val owner = inputData.getString("owner") ?: return@withContext Result.failure()
            if (app.sessions.read()?.scope != owner)
                return@withContext if (app.sessions.hasConnection(owner)) Result.retry()
                else Result.failure()
            val fileId = inputData.getInt("fileId", 0)
            val baseline =
                wireJson.decodeFromString<EbookProgress>(
                    inputData.getString("baseline") ?: return@withContext Result.failure()
                )
            val value =
                wireJson.decodeFromString<EbookProgressWrite>(
                    inputData.getString("value") ?: return@withContext Result.failure()
                )
            try {
                val saved =
                    EbookStorage(app, owner).book(fileId) ?: return@withContext Result.failure()
                val latest =
                    app.api.ebookDetail(saved.bookId, owner).files.firstOrNull { it.id == fileId }
                if (latest?.revision != saved.file.revision)
                    return@withContext Result.failure(
                        workDataOf(
                            "message" to
                                "The server file changed. Download it again before sharing progress."
                        )
                    )
                val current = app.api.ebookProgress(fileId, owner)
                if (!sameReadingProgress(current, baseline))
                    return@withContext Result.failure(
                        workDataOf(
                            "message" to
                                "Server progress changed. Review it again before publishing."
                        )
                    )
            } catch (_: IOException) {
                if (app.sessions.read()?.scope != owner && app.sessions.hasConnection(owner))
                    return@withContext Result.retry()
                return@withContext if (runAttemptCount < 3) Result.retry()
                else
                    Result.failure(
                        workDataOf(
                            "message" to "Could not reach the server. Review progress and retry."
                        )
                    )
            }
            try {
                app.api.saveEbookProgress(fileId, owner, value)
                Result.success(workDataOf("message" to "Reading progress published."))
            } catch (_: IOException) {
                // A timed-out write may have succeeded. Let the user re-read the server instead of
                // replaying it.
                Result.failure(
                    workDataOf(
                        "message" to
                            "The publish result is uncertain. Refresh server progress before trying again."
                    )
                )
            }
        }
}

fun queueReadingSync(
    context: Context,
    owner: String,
    fileId: Int,
    baseline: EbookProgress,
    value: EbookProgressWrite,
): String {
    val work =
        OneTimeWorkRequestBuilder<ReadingSyncWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setInputData(
                workDataOf(
                    "owner" to owner,
                    "fileId" to fileId,
                    "baseline" to wireJson.encodeToString(baseline),
                    "value" to wireJson.encodeToString(value),
                )
            )
            .addTag("account-$owner")
            .build()
    val manager = WorkManager.getInstance(context)
    val name = "reading-sync-$owner-$fileId"
    manager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, work).result.get()
    return manager.getWorkInfosForUniqueWork(name).get().let { jobs ->
        (jobs.firstOrNull { !it.state.isFinished } ?: jobs.first { it.id == work.id }).id.toString()
    }
}

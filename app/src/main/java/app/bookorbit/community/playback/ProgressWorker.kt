package app.bookorbit.community.playback

import android.content.Context
import androidx.work.*
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.*
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProgressWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val app = applicationContext as OrbitApplication
            val scope = inputData.getString("scope") ?: return@withContext Result.failure()
            val bookId = inputData.getInt("bookId", 0)
            if (app.sessions.read()?.scope != scope)
                return@withContext if (app.sessions.hasConnection(scope)) Result.retry()
                else Result.failure()
            val storage = AudioStorage(app, scope)
            try {
                repeat(3) {
                    val position =
                        storage.position(bookId)?.takeIf { it.dirty }
                            ?: return@withContext Result.success()
                    val remote =
                        try {
                            app.api.saveProgress(bookId, position.write(), scope)
                        } catch (error: ApiFailure) {
                            if (error.status == 409) storage.markProgressConflict(bookId, position)
                            throw error
                        }
                    storage.acknowledge(bookId, position, remote)
                }
                Result.retry()
            } catch (error: ApiFailure) {
                if (
                    app.sessions.hasConnection(scope) &&
                        (app.sessions.read()?.scope != scope ||
                            (error.status == 401 &&
                                scope in app.api.reauthenticationRequired.value))
                )
                    return@withContext Result.retry()
                Result.failure(workDataOf("error" to error.message))
            } catch (_: IOException) {
                Result.retry()
            }
        }
}

fun enqueueProgress(context: Context, scope: String, bookId: Int) {
    WorkManager.getInstance(context)
        .enqueueUniqueWork(
            "progress-$scope-$bookId",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ProgressWorker>()
                .setInputData(workDataOf("scope" to scope, "bookId" to bookId))
                .addTag("account-$scope")
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build(),
        )
}

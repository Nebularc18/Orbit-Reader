package app.bookorbit.community.playback

import android.content.Context
import androidx.work.*
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ListeningSessionWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    companion object {
        private val syncLock = Mutex()
    }

    override suspend fun doWork(): Result =
        syncLock.withLock {
            withContext(Dispatchers.IO) {
                val app = applicationContext as OrbitApplication
                val owner = inputData.getString("scope") ?: return@withContext Result.failure()
                if (app.sessions.read()?.scope != owner) return@withContext Result.retry()
                val storage = ListeningSessionStorage(app, owner)
                try {
                    for (pending in storage.pending()) {
                        app.api.saveListeningSession(pending.fileId, pending.value, owner)
                        storage.acknowledge(pending)
                    }
                    if (storage.pending(1).isEmpty()) Result.success() else Result.retry()
                } catch (_: IOException) {
                    // API failures also retain the queue until credentials or server support
                    // recover.
                    Result.retry()
                }
            }
        }
}

fun enqueueListeningSessions(context: Context, owner: String) {
    // The periodic drain covers a checkpoint written as the immediate worker exits.
    WorkManager.getInstance(context)
        .enqueueUniquePeriodicWork(
            "listening-sessions-recovery-$owner",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ListeningSessionWorker>(15, TimeUnit.MINUTES)
                .setInputData(workDataOf("scope" to owner))
                .addTag("account-$owner")
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build(),
        )
    WorkManager.getInstance(context)
        .enqueueUniqueWork(
            "listening-sessions-$owner",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ListeningSessionWorker>()
                .setInputData(workDataOf("scope" to owner))
                .addTag("account-$owner")
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build(),
        )
}

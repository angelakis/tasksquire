package com.taskwarrior.android.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.taskwarrior.android.data.TaskDatabase
import com.taskwarrior.android.data.TaskRepository
import java.util.concurrent.TimeUnit

class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repository = TaskRepository(TaskDatabase.getInstance(applicationContext).taskDao())
        val syncManager = SyncManager(applicationContext, repository)

        if (!syncManager.isAutoSyncEnabled()) {
            return Result.success()
        }

        return when (syncManager.sync()) {
            is SyncResult.Success,
            SyncResult.NotModified,
            SyncResult.NoServerConfigured -> Result.success()
            is SyncResult.Error -> {
                if (runAttemptCount < MAX_WORKER_RETRIES) Result.retry() else Result.success()
            }
        }
    }

    companion object {
        const val UNIQUE_MUTATION_SYNC_WORK = "mutation_sync_work"
        const val UNIQUE_FOREGROUND_SYNC_WORK = "foreground_sync_work"
        const val UNIQUE_PERIODIC_SYNC_WORK = "periodic_sync_work"

        private const val PERIODIC_SYNC_HOURS = 6L
        private const val MAX_WORKER_RETRIES = 3

        private fun networkConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        private fun oneTimeRequest(): OneTimeWorkRequest =
            androidx.work.OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

        private fun periodicRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<SyncWorker>(PERIODIC_SYNC_HOURS, TimeUnit.HOURS)
                .setConstraints(networkConstraints())
                .build()

        fun enqueueMutationSync(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_MUTATION_SYNC_WORK,
                ExistingWorkPolicy.REPLACE,
                oneTimeRequest()
            )
        }

        fun enqueueForegroundSync(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_FOREGROUND_SYNC_WORK,
                ExistingWorkPolicy.KEEP,
                oneTimeRequest()
            )
        }

        fun schedulePeriodicSync(context: Context, enabled: Boolean) {
            val workManager = WorkManager.getInstance(context)
            if (enabled) {
                workManager.enqueueUniquePeriodicWork(
                    UNIQUE_PERIODIC_SYNC_WORK,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    periodicRequest()
                )
            } else {
                workManager.cancelUniqueWork(UNIQUE_PERIODIC_SYNC_WORK)
                workManager.cancelUniqueWork(UNIQUE_MUTATION_SYNC_WORK)
                workManager.cancelUniqueWork(UNIQUE_FOREGROUND_SYNC_WORK)
            }
        }
    }
}

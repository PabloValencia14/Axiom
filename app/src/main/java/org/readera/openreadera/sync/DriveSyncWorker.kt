package org.readera.openreadera.sync

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class DriveSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "DriveSyncWorker"
        const val PERIODIC_WORK_NAME = "openreadera_periodic_drive_sync"
        const val IMMEDIATE_WORK_NAME = "openreadera_immediate_drive_sync"

        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            // 15 minutes is the minimum periodic interval allowed by Android WorkManager
            val syncRequest = PeriodicWorkRequestBuilder<DriveSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                syncRequest
            )
            Log.i(TAG, "Periodic Google Drive sync scheduled every 15 minutes with network constraint")
        }

        fun enqueueImmediateSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val immediateRequest = OneTimeWorkRequestBuilder<DriveSyncWorker>()
                .setInitialDelay(20, TimeUnit.SECONDS)
                .setConstraints(constraints)
                .build()

            context.getSharedPreferences("drive_sync_requests", Context.MODE_PRIVATE).edit()
                .putString("requestId", java.util.UUID.randomUUID().toString()).commit()
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                immediateRequest
            )
            Log.i(TAG, "Immediate Google Drive sync enqueued")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val syncManager = GoogleDriveSyncManager(applicationContext)

        if (!syncManager.isAutoSyncEnabled) {
            Log.d(TAG, "Auto-sync is disabled by user setting. Skipping.")
            return@withContext Result.success()
        }

        if (!syncManager.isDriveConnected()) {
            Log.d(TAG, "Google Drive is not connected (no SAF folder linked and no active OAuth token). Skipping.")
            return@withContext Result.success()
        }

        Log.i(TAG, "Starting automatic Google Drive synchronization...")

        val requests = applicationContext.getSharedPreferences("drive_sync_requests", Context.MODE_PRIVATE)
        try {
            var syncResult: kotlin.Result<DriveSyncStats>
            var started: String?
            do {
                started = requests.getString("requestId", null)
                syncResult = syncManager.performSync()
            } while (syncResult.isSuccess && requests.getString("requestId", null) != started)
            // Periodic work also guarantees a later retry if a request races with completion.

            if (syncResult.isSuccess) {
                val stats = syncResult.getOrNull()
                Log.i(TAG, "Full sync completed: ${stats?.booksSynced} books processed, timestamp=${stats?.lastSyncTimestamp}")
                Result.success()
            } else {
                Log.w(TAG, "Full sync error: ${syncResult.exceptionOrNull()?.message}")
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Error executing Google Drive background sync", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

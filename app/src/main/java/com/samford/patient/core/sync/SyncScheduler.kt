package com.samford.patient.core.sync

import android.content.Context
import androidx.work.BackoffPolicy as WorkBackoff
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.samford.patient.SamfordApp
import java.util.concurrent.TimeUnit

/**
 * Background sync without FCM. Push is simulated by polling:
 * - a periodic job every 30 minutes with a 10-minute flex window and a stable random per-install
 *   phase, only on a connected network and when the battery is not low (Doze stretches it further);
 * - a one-time retry at the jittered backoff time after a failure;
 * - a Wi-Fi-only prefetch of new handwritten pages so they open offline.
 */
class SyncScheduler(private val context: Context) {

    private val workManager get() = WorkManager.getInstance(context)

    fun schedulePeriodic(installId: String) {
        val period = TimeUnit.MINUTES.toMillis(PERIOD_MINUTES)
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES, FLEX_MINUTES, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setInitialDelay(SyncPhase.initialDelayMillis(installId, period), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(WorkBackoff.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .addTag(TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun scheduleRetry(atMillis: Long, nowMillis: Long) {
        val delay = (atMillis - nowMillis).coerceAtLeast(MIN_RETRY_MS)
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(RETRY, ExistingWorkPolicy.REPLACE, request)
    }

    fun schedulePrefetch() {
        val request = OneTimeWorkRequestBuilder<PrefetchWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(PREFETCH, ExistingWorkPolicy.KEEP, request)
    }

    fun cancelAll() {
        workManager.cancelUniqueWork(PERIODIC)
        workManager.cancelUniqueWork(RETRY)
        workManager.cancelUniqueWork(PREFETCH)
        workManager.cancelAllWorkByTag(TAG)
    }

    companion object {
        const val PERIOD_MINUTES = 30L
        const val FLEX_MINUTES = 10L
        private const val MIN_RETRY_MS = 30_000L
        const val TAG = "samford-sync"
        const val PERIODIC = "samford-periodic-sync"
        const val RETRY = "samford-retry-sync"
        const val PREFETCH = "samford-prefetch"
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as SamfordApp).container
        container.syncCoordinator.run(force = false, reason = "background")
        return Result.success()
    }
}

class PrefetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as SamfordApp).container
        container.prefetcher.prefetchRecent()
        return Result.success()
    }
}

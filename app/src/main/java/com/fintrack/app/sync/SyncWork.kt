package com.fintrack.app.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/** Background sync run. Offline → retried with backoff; other outcomes are recorded and final. */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runner: SyncRunner,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (runner.run()) {
        SyncOutcome.Offline -> Result.retry()
        else -> Result.success()
    }
}

/** Schedules sync (design §6.3): soon after local changes, on app start, every 6 hours, on demand. */
class SyncScheduler(private val workManager: WorkManager) {

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Debounced: a new request replaces a waiting one, so a burst of edits causes one run. */
    fun requestSoon() = enqueueOnce(delaySeconds = DEBOUNCE_SECONDS)

    fun syncNow() = enqueueOnce(delaySeconds = 0)

    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIODIC_HOURS, TimeUnit.HOURS)
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelAll() {
        workManager.cancelUniqueWork(ONE_TIME_WORK)
        workManager.cancelUniqueWork(PERIODIC_WORK)
    }

    /** True while a sync run is executing. */
    val isRunning: Flow<Boolean> = workManager.getWorkInfosFlow(
        androidx.work.WorkQuery.fromUniqueWorkNames(ONE_TIME_WORK, PERIODIC_WORK),
    ).map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }

    private fun enqueueOnce(delaySeconds: Long) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(network)
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        // REPLACE: a waiting request is the debounce; a running one is cancelled and restarted,
        // which is safe because every page and cursor update is committed atomically.
        workManager.enqueueUniqueWork(ONE_TIME_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val ONE_TIME_WORK = "fintrack-sync"
        const val PERIODIC_WORK = "fintrack-sync-periodic"
        const val DEBOUNCE_SECONDS = 10L
        const val PERIODIC_HOURS = 6L
        const val BACKOFF_SECONDS = 30L
    }
}

package com.odoocompanion.softphone

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.odoocompanion.CompanionApp
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Reconciles again later while the softphone should be running and is not: the
 * system refused its start, or the microphone is not granted yet. Nothing else
 * would: the reconciler runs at startup, at boot, on a managed push and from
 * the screen, so a refused boot start left the line silent until the next of
 * those. It judges by what it sees afterwards, not by what it was told, and
 * stops once the line is up or no longer wanted.
 */
class SoftphoneRetry(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = CompanionApp.from(applicationContext)
        app.applyConfiguration()
        // the service starts after the call that asked for it returns
        delay(SETTLE_MILLIS)
        val stalled = app.softphones.current() != null && !SoftphoneService.isRunning
        return if (stalled) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "softphone-retry"
        private const val DELAY_MINUTES = 15L
        private const val SETTLE_MILLIS = 10_000L

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SoftphoneRetry>()
                    .setInitialDelay(DELAY_MINUTES, TimeUnit.MINUTES)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, DELAY_MINUTES, TimeUnit.MINUTES)
                    .build(),
            )
        }
    }
}

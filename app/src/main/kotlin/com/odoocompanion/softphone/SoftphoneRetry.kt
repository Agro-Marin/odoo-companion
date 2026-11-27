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

class SoftphoneRetry(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = CompanionApp.from(applicationContext)
        app.applyConfiguration()
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

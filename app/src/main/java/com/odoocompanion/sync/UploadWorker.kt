package com.odoocompanion.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.odoocompanion.CompanionApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UploadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = CompanionApp.from(applicationContext)
        val settings = app.config.current()
        if (!settings.isEnrolled) return@withContext Result.success()

        val drainer = OutboxDrainer(
            dao = app.database.outbox(),
            client = app.client,
            settingsProvider = { app.config.current() },
            learnPayloadLimit = { app.config.learnPayloadLimit(it) },
        )
        val report = drainer.drainAll()
        app.config.recordUpload(System.currentTimeMillis(), report.delivered, report.lastError)

        if (report.deadLettered > 0 ||
            report.skipped.isNotEmpty() ||
            report.undecodable.isNotEmpty() ||
            report.lost > 0
        ) {
            Log.w(
                TAG,
                "Drain finished with skipped=${report.skipped} " +
                    "undecodable=${report.undecodable} deadLettered=${report.deadLettered} " +
                    "unreachable=${report.unreachable} lost=${report.lost}",
            )
        }
        if (report.accepted.isNotEmpty() ||
            report.duplicates.isNotEmpty() ||
            report.revived > 0 ||
            report.discarded > 0 ||
            report.purged > 0
        ) {
            Log.i(
                TAG,
                "Drain finished with accepted=${report.accepted} " +
                    "duplicates=${report.duplicates} revived=${report.revived} " +
                    "discarded=${report.discarded} purged=${report.purged}",
            )
        }

        when (report.outcome) {
            OutboxDrainer.Outcome.RETRY -> Result.retry()

            OutboxDrainer.Outcome.DONE -> {
                if (report.moreWorkPending) {
                    SyncScheduler.continueUpload(
                        applicationContext,
                        app.config.current().wifiOnlyUploads,
                    )
                }
                Result.success()
            }
        }
    }

    private companion object {
        const val TAG = "UploadWorker"
    }
}

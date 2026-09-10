package com.odoocompanion.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.odoocompanion.CompanionApp
import com.odoocompanion.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CallLogSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = CompanionApp.from(applicationContext)
        val settings = app.config.current()
        if (!settings.isEnrolled || !settings.callLogEnabled) return@withContext Result.success()
        if (!hasPermission()) return@withContext Result.success()

        val since = app.config.callLogCursor()
        val seen = app.config.callLogSeen()
        // Read behind the cursor, because a call that was still in progress
        // when the last pass ran is written afterwards carrying an earlier
        // date. What keeps that re-read from queueing the same calls every
        // half hour is `seen`, not the cursor.
        val from = (since - CallLogReader.LATE_WRITE_LOOKBACK_MILLIS).coerceAtLeast(0)
        val batch = CallLogReader(applicationContext.contentResolver)
            .readSince(from, alreadyQueued = seen)

        if (batch.entries.isNotEmpty()) {
            app.database.outbox().insertAll(batch.entries)
            SyncScheduler.uploadNow(applicationContext, settings.wifiOnlyUploads)
        }
        // The reader starts its cursor at what it was given, so an empty log
        // would otherwise walk the stored cursor back by the lookback.
        if (batch.cursor > since) app.config.setCallLogCursor(batch.cursor)
        app.config.setCallLogSeen(
            CallLogReader.forget(
                seen + batch.queuedKeys,
                maxOf(batch.cursor, since) - CallLogReader.LATE_WRITE_LOOKBACK_MILLIS,
            ),
        )

        if (batch.moreWaiting) SyncScheduler.collectCallLogNow(applicationContext)
        Result.success()
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED
}

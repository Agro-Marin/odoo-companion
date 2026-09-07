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
        val batch = CallLogReader(applicationContext.contentResolver).readSince(since)

        if (batch.entries.isNotEmpty()) {
            app.database.outbox().insertAll(batch.entries)
            SyncScheduler.uploadNow(applicationContext, settings.wifiOnlyUploads)
        }
        if (batch.cursor > since) app.config.setCallLogCursor(batch.cursor)

        if (batch.moreWaiting) SyncScheduler.collectCallLogNow(applicationContext)
        Result.success()
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED
}

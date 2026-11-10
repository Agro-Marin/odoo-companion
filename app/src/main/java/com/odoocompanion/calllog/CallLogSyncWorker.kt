package com.odoocompanion.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.odoocompanion.CompanionApp
import com.odoocompanion.config.Settings
import com.odoocompanion.sync.SyncScheduler
import com.odoocompanion.system.debug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class CallLogSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = CompanionApp.from(applicationContext)
        val settings = app.config.currentOrNull() ?: return@withContext Result.retry()
        if (!settings.isEnrolled || !settings.callLogEnabled) return@withContext Result.success()
        if (!hasPermission()) return@withContext Result.success()
        reading.withLock { collect(app, settings) }
        Result.success()
    }

    private suspend fun collect(app: CompanionApp, settings: Settings) {
        val reader = CallLogReader(applicationContext.contentResolver)
        val since = startingId(app, reader)
        val batch = reader.readSince(since)
        debug(TAG) {
            "read after id $since: ${batch.entries.size} call(s), cursor now ${batch.cursor}, " +
                "more waiting=${batch.moreWaiting}"
        }

        if (batch.entries.isNotEmpty()) {
            app.database.outbox().insertAll(
                batch.entries.map { it.copy(enrollment = settings.enrollment) },
            )
            if (SyncScheduler.UPLOAD_FOLLOWS !in tags) {
                SyncScheduler.uploadNow(applicationContext, settings.wifiOnlyUploads)
            }
        }
        if (batch.cursor > since) app.config.setCallLogIdCursor(batch.cursor)

        if (batch.moreWaiting) SyncScheduler.collectCallLogNow(applicationContext)
    }

    private suspend fun startingId(app: CompanionApp, reader: CallLogReader): Long {
        val stored = app.config.callLogIdCursor()
        if (stored == null) {
            val seeded = reader.idAt(app.config.callLogCursor())
            app.config.setCallLogIdCursor(seeded)
            Log.i(TAG, "Carried the call-log cursor across to row id $seeded")
            return seeded
        }
        val newest = reader.newestId()
        if (newest != null && newest < stored) {
            Log.w(
                TAG,
                "Call log restarted at id $newest, below the stored cursor " +
                    "$stored; reading it from the beginning",
            )
            app.config.setCallLogIdCursor(0)
            return 0
        }
        return stored
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "CallLogSyncWorker"

        val reading = Mutex()
    }
}

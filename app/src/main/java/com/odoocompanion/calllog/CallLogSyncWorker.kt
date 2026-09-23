package com.odoocompanion.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.odoocompanion.CompanionApp
import com.odoocompanion.sync.SyncScheduler
import com.odoocompanion.system.debug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CallLogSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = CompanionApp.from(applicationContext)
        val settings = app.config.current()
        if (!settings.isEnrolled || !settings.callLogEnabled) return@withContext Result.success()
        if (!hasPermission()) return@withContext Result.success()

        val reader = CallLogReader(applicationContext.contentResolver)
        val since = startingId(app, reader)
        val batch = reader.readSince(since)
        debug(TAG) {
            "read after id $since: ${batch.entries.size} call(s), cursor now ${batch.cursor}, " +
                "more waiting=${batch.moreWaiting}"
        }

        if (batch.entries.isNotEmpty()) {
            app.database.outbox().insertAll(batch.entries)
            if (SyncScheduler.UPLOAD_FOLLOWS !in tags) {
                SyncScheduler.uploadNow(applicationContext, settings.wifiOnlyUploads)
            }
        }
        if (batch.cursor > since) app.config.setCallLogIdCursor(batch.cursor)

        if (batch.moreWaiting) SyncScheduler.collectCallLogNow(applicationContext)
        Result.success()
    }

    // Where this pass starts, which is the stored id except in the two cases
    // that would otherwise leave the phone silent for good.
    private suspend fun startingId(app: CompanionApp, reader: CallLogReader): Long {
        val stored = app.config.callLogIdCursor()
        if (stored == null) {
            // First pass on this build: carry the timestamp cursor across by
            // asking the provider which id it names.
            val seeded = reader.idAt(app.config.callLogCursor())
            app.config.setCallLogIdCursor(seeded)
            Log.i(TAG, "Carried the call-log cursor across to row id $seeded")
            return seeded
        }
        val newest = reader.newestId()
        if (newest != null && newest < stored) {
            // Ids only ever climb, and a deleted one is never handed out
            // again, so an id below the cursor means this is not the table the
            // cursor was counting through -- the call log was cleared, or
            // restored onto the handset. Reading on from the old cursor would
            // return nothing, for ever, while reporting success.
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
    }
}

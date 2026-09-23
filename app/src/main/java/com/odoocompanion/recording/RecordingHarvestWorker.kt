package com.odoocompanion.recording

import android.content.Context
import android.os.Environment
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.odoocompanion.CompanionApp
import com.odoocompanion.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RecordingHarvestWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = CompanionApp.from(applicationContext)
        val settings = app.config.current()
        if (!settings.isEnrolled || !settings.recordingsEnabled) {
            return@withContext Result.success()
        }

        val queued = RecordingHarvest(
            dao = app.database.outbox(),
            root = Environment.getExternalStorageDirectory(),
        ).queueNew()

        if (queued > 0 && SyncScheduler.UPLOAD_FOLLOWS !in tags) {
            SyncScheduler.uploadNow(applicationContext, settings.wifiOnlyUploads)
        }
        Result.success()
    }
}

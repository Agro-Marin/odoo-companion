package com.odoocompanion.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.odoocompanion.CompanionApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CallEndedWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val settings = CompanionApp.from(applicationContext).config.currentOrNull()
        if (settings != null) SyncScheduler.afterCall(applicationContext, settings)
        if (settings == null || SyncScheduler.wantsCallEndedTrigger(applicationContext, settings)) {
            SyncScheduler.rearmCallEndedTrigger(applicationContext)
        }
        Result.success()
    }
}

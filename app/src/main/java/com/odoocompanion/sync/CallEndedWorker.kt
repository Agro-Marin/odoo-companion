package com.odoocompanion.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.odoocompanion.CompanionApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Runs when the call log changes, which is when a call ends: the dialer writes
// the call's row as it hangs up. A content-URI trigger fires once, so the
// worker arms the next one before returning.
class CallEndedWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val settings = CompanionApp.from(applicationContext).config.currentOrNull()
        if (settings != null) SyncScheduler.afterCall(applicationContext, settings)
        // An unreadable configuration re-arms too: the next call asks again,
        // and the reconcile cancels the trigger once it is no longer wanted.
        if (settings == null || SyncScheduler.wantsCallEndedTrigger(applicationContext, settings)) {
            SyncScheduler.rearmCallEndedTrigger(applicationContext)
        }
        Result.success()
    }
}

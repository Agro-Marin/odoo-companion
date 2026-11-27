package com.odoocompanion.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.odoocompanion.CompanionApp
import com.odoocompanion.config.FormValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException

class LabEnrollReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val form = FormValues(
            baseUrl = intent.getStringExtra("base_url").orEmpty(),
            identifier = intent.getStringExtra("identifier").orEmpty(),
            token = intent.getStringExtra("token").orEmpty(),
            callLogEnabled = intent.getBooleanExtra("call_log", true),
            recordingsEnabled = false,
            wifiOnlyUploads = false,
            locationIntervalSeconds = intent.longOrNull("interval_seconds"),
            uploadWindowSeconds = intent.longOrNull("upload_window_seconds"),
        )
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                Log.i(TAG, "Lab enrolment: ${CompanionApp.from(context).saveForm(form).await()}")
            } catch (failure: IOException) {
                Log.w(TAG, "Lab enrolment could not be stored", failure)
            } finally {
                pending.finish()
            }
        }
    }

    private fun Intent.longOrNull(key: String): Long? =
        if (hasExtra(key)) getLongExtra(key, 0) else null

    private companion object {
        const val TAG = "LabEnrollReceiver"
    }
}

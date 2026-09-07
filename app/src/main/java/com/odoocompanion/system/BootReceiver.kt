package com.odoocompanion.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.odoocompanion.CompanionApp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        val pending = goAsync()

        val scope = CoroutineScope(
            Dispatchers.Default +
                CoroutineExceptionHandler { _, cause ->
                    Log.e(TAG, "Could not resume reporting after ${intent.action}", cause)
                },
        )
        scope.launch {
            try {
                val app = CompanionApp.from(context)
                app.startup.join()
                app.applyConfiguration()
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"

        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}

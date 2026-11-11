package com.odoocompanion.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast
import androidx.core.content.IntentCompat
import com.odoocompanion.R

// What Android answers about an update this app handed it: the confirmation
// to show the person, or how it ended.
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, NO_STATUS)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm =
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                        ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }

            PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "Update installed")

            // The person said no to Android's confirmation: Home offers it again.
            PackageInstaller.STATUS_FAILURE_ABORTED -> Log.i(TAG, "Update declined")

            // A key that is not the installed build's lands here, as
            // STATUS_FAILURE_CONFLICT or _INCOMPATIBLE: say it, since nothing
            // else on the phone would.
            else -> {
                Log.w(TAG, "Update not installed: status $status, $message")
                Toast.makeText(context, R.string.update_install_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private companion object {
        const val TAG = "UpdateReceiver"
        const val NO_STATUS = -999
    }
}

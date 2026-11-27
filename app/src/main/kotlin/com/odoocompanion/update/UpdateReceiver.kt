package com.odoocompanion.update

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast
import androidx.core.content.IntentCompat
import com.odoocompanion.R

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, Updater.NO_SESSION)
        if (!Updater.owns(context, session)) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, NO_STATUS)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm =
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                try {
                    if (confirm != null) {
                        Updater.awaitConfirmation(context, session, Intent(confirm))
                        UpdateNotices.askToConfirm(context, Intent(confirm))
                        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        return
                    }
                } catch (_: ActivityNotFoundException) {
                    Log.w(TAG, "The installer confirmation activity is unavailable")
                } catch (_: SecurityException) {
                    Log.w(TAG, "The installer confirmation activity could not be opened")
                }
                try {
                    context.packageManager.packageInstaller.abandonSession(session)
                } catch (_: SecurityException) {
                    Log.w(TAG, "Android no longer grants access to the installer session")
                } finally {
                    Updater.finish(context, session, installed = false)
                }
                Toast.makeText(context, R.string.update_install_failed, Toast.LENGTH_LONG).show()
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Updater.finish(context, session, installed = true)
                Log.i(TAG, "Update installed")
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Log.i(TAG, "Update declined")
                Updater.finish(context, session, installed = false)
            }

            else -> {
                Log.w(TAG, "Update not installed: status $status")
                Updater.finish(context, session, installed = false)
                Toast.makeText(context, R.string.update_install_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private companion object {
        const val TAG = "UpdateReceiver"
        const val NO_STATUS = -999
    }
}

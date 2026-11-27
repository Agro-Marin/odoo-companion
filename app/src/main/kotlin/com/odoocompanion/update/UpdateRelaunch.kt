package com.odoocompanion.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.pm.PackageInfoCompat
import com.odoocompanion.R
import com.odoocompanion.ui.MainActivity

object UpdateRelaunch {
    fun afterReplace(context: Context) {
        if (Updater.takeRelaunch(context) == null) return
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            context.startActivity(open)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Android would not reopen the app after its update", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Android would not reopen the app after its update", e)
        }
        notify(context, open)
    }

    fun dismiss(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    private fun notify(context: Context, open: Intent) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_updates),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val tap = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_update)
            .setContentTitle(
                context.getString(R.string.update_installed_title, info.versionName.orEmpty()),
            )
            .setContentText(context.getString(R.string.update_installed_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to say the update is done", e)
        }
        Log.i(TAG, "Updated to ${PackageInfoCompat.getLongVersionCode(info)}")
    }

    private const val TAG = "UpdateRelaunch"
    private const val CHANNEL_ID = "updates"
    private const val NOTIFICATION_ID = 7_301
}

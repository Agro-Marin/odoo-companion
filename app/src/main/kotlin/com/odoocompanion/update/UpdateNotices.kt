package com.odoocompanion.update

import android.app.Notification
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

object UpdateNotices {
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
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        post(
            context,
            INSTALLED_ID,
            open,
            context.getString(R.string.update_installed_title, info.versionName.orEmpty()),
            context.getString(R.string.update_installed_text),
        )
        Log.i(TAG, "Updated to ${PackageInfoCompat.getLongVersionCode(info)}")
    }

    fun dismiss(context: Context) = NotificationManagerCompat.from(context).cancel(INSTALLED_ID)

    fun askToConfirm(context: Context, confirm: Intent) {
        post(
            context,
            CONFIRM_ID,
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            context.getString(R.string.update_confirm_title),
            context.getString(R.string.update_confirm_text),
        )
    }

    fun withdrawConfirmation(context: Context) =
        NotificationManagerCompat.from(context).cancel(CONFIRM_ID)

    private fun post(context: Context, id: Int, open: Intent, title: String, text: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_updates),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val tap = PendingIntent.getActivity(
            context,
            id,
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_update)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to post an update notification", e)
        }
    }

    private const val TAG = "UpdateNotices"
    private const val CHANNEL_ID = "updates"
    private const val INSTALLED_ID = 7_301
    private const val CONFIRM_ID = 7_302
}

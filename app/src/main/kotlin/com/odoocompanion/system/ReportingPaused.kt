package com.odoocompanion.system

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.odoocompanion.R
import com.odoocompanion.ui.MainActivity

object ReportingPaused {
    const val ACTION_RESUME = "com.odoocompanion.RESUME_REPORTING"

    @Volatile
    private var pending = false

    fun notice(context: Context) {
        pending = true
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_paused),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val open = Intent(context, MainActivity::class.java)
            .setAction(ACTION_RESUME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.reporting_paused_title))
            .setContentText(context.getString(R.string.reporting_paused_text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    NOTIFICATION_ID,
                    open,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to say reporting could not start", e)
        }
    }

    fun consume(context: Context, intent: Intent?): Boolean {
        val asked = intent?.action == ACTION_RESUME
        if (asked) intent.action = null
        val due = pending || asked
        pending = false
        if (due) NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        return due
    }

    private const val TAG = "ReportingPaused"
    private const val CHANNEL_ID = "paused"
    private const val NOTIFICATION_ID = 7_401
}

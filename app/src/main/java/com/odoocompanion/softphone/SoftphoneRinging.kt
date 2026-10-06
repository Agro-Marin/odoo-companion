package com.odoocompanion.softphone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.odoocompanion.R

/**
 * Rings an incoming company call: the app's own job, since the system's
 * ringing UI is not shown for a self-managed call and the SIP stack's native
 * ringing needs androidx.media, which this app does not ship (it falls back to
 * a ringtone file the APK leaves out). The channel carries the handset's
 * ringtone and a vibration; the notification repeats both until it is answered,
 * declined or the caller gives up.
 */
object SoftphoneRinging {
    private const val TAG = "SoftphoneRinging"

    // A channel's sound is fixed once it exists, so the first build's silent
    // channel is replaced under a new id rather than edited.
    const val CHANNEL_ID = "softphone_incoming"
    private const val RETIRED_CHANNEL_ID = "softphone_ringing"
    private const val NOTIFICATION_ID = 3
    private val VIBRATION = longArrayOf(0, 1_000, 1_000)

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(RETIRED_CHANNEL_ID)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_softphone_ringing),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                vibrationPattern = VIBRATION
            },
        )
    }

    /**
     * Rings, over the lock screen when the system lets it. [silent] keeps the
     * notification and drops the sound: the volume key silences a ringing call.
     */
    fun ring(context: Context, remote: String?, silent: Boolean = false) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !manager.canUseFullScreenIntent()
        ) {
            Log.w(TAG, "Full-screen calls not allowed for this app; ringing as a heads-up only")
        }
        val screen = PendingIntent.getActivity(
            context,
            0,
            InCallActivity.intent(context),
            PendingIntent.FLAG_IMMUTABLE,
        )
        // answering opens the call screen, which is what lets the service take
        // the microphone from the background
        val answer = PendingIntent.getActivity(
            context,
            1,
            InCallActivity.intent(context).setAction(InCallActivity.ACTION_ANSWER),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val decline = PendingIntent.getService(
            context,
            2,
            SoftphoneService.declineIntent(context),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val caller = Person.Builder()
            .setName(remote ?: context.getString(R.string.softphone_ringing))
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.softphone_ringing))
            .setContentText(remote.orEmpty())
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(silent)
            .setFullScreenIntent(screen, true)
            .setContentIntent(screen)
            .build()
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "The system refused the ringing notification", e)
        }
    }

    fun stop(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}

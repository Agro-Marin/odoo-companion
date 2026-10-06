package com.odoocompanion.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.BatteryManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.odoocompanion.CompanionApp
import com.odoocompanion.R
import com.odoocompanion.config.DEFAULT_LOCATION_INTERVAL_SECONDS
import com.odoocompanion.config.DEFAULT_MIN_MOVE_METRES
import com.odoocompanion.config.DEFAULT_UPLOAD_WINDOW_SECONDS
import com.odoocompanion.net.LocationFix
import com.odoocompanion.sync.SyncScheduler
import com.odoocompanion.system.debug
import com.odoocompanion.ui.MainActivity
import kotlinx.coroutines.launch

class LocationForegroundService : LifecycleService() {
    private val provider by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private val queue by lazy {
        LocationQueue(CompanionApp.from(applicationContext).database.outbox())
    }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val app = CompanionApp.from(applicationContext)

            val battery = batteryLevel()
            val fixes = result.locations.map { fixOf(it, battery) }
            if (fixes.isEmpty()) return
            lifecycleScope.launch {
                // Unreadable settings still keep the fix, at the defaults; only
                // the upload waits, since it would read them first anyway.
                val settings = app.config.currentOrNull()
                val due = queue.record(
                    fixes,
                    settings?.uploadWindowSeconds ?: DEFAULT_UPLOAD_WINDOW_SECONDS,
                    settings?.minMoveMetres ?: DEFAULT_MIN_MOVE_METRES,
                )
                if (due && settings != null) {
                    SyncScheduler.uploadNow(applicationContext, settings.wifiOnlyUploads)
                }
            }
        }
    }

    // Set when onCreate could not run the service: onStartCommand still follows,
    // and must neither ask for fixes on a stopping service nor ask to be
    // restarted into the same refusal.
    private var refused = false

    // The interval the provider was last asked for. Every reconcile starts the
    // service again -- each save, each policy push -- and asking anew with the
    // same interval only resets the provider's schedule.
    private var requestedSeconds: Long? = null

    override fun onCreate() {
        super.onCreate()
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: SecurityException) {
            Log.w(TAG, "Foreground start refused; needs background location", e)
            refuse()
            return
        }
        if (!canRun(this)) {
            Log.w(TAG, "Location permission revoked since start was requested; stopping")
            refuse()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (refused) return START_NOT_STICKY
        lifecycleScope.launch {
            val settings = CompanionApp.from(applicationContext).config.currentOrNull()
            val seconds = settings?.locationIntervalSeconds ?: DEFAULT_LOCATION_INTERVAL_SECONDS
            if (seconds != requestedSeconds) requestUpdates(seconds)
        }
        return START_STICKY
    }

    private fun refuse() {
        refused = true
        stopSelf()
    }

    override fun onDestroy() {
        provider.removeLocationUpdates(callback)
        super.onDestroy()
    }

    private fun requestUpdates(intervalSeconds: Long) {
        try {
            provider.requestLocationUpdates(
                requestFor(intervalSeconds),
                callback,
                mainLooper,
            )
            requestedSeconds = intervalSeconds
            debug(TAG) { "asked the provider for a fix every $intervalSeconds s" }
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission not granted; stopping reporting", e)
            refuse()
        }
    }

    private fun batteryLevel(): Int? {
        val manager = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level.takeIf { it in 0..100 }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_location),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_location_title))
            .setContentText(getString(R.string.notification_location_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        fun requestFor(intervalSeconds: Long): LocationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            intervalSeconds * 1_000,
        )
            .setMinUpdateIntervalMillis(intervalSeconds * 1_000)
            .setWaitForAccurateLocation(false)
            .build()

        fun fixOf(location: Location, batteryLevel: Int?) = LocationFix(
            latitude = location.latitude,
            longitude = location.longitude,
            timestamp = location.time,
            accuracy = location.accuracy.takeIf { location.hasAccuracy() },
            altitude = location.altitude.takeIf { location.hasAltitude() },
            speed = location.speed.takeIf { location.hasSpeed() },
            heading = location.bearing.takeIf { location.hasBearing() },
            batteryLevel = batteryLevel,
        )

        private const val TAG = "LocationService"
        private const val CHANNEL_ID = "location"
        private const val NOTIFICATION_ID = 1

        private val LOCATION_PERMISSIONS = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        fun canRun(context: Context): Boolean = LOCATION_PERMISSIONS.any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        fun start(context: Context) {
            if (!canRun(context)) {
                Log.w(TAG, "Not starting location reporting: no location permission granted")
                return
            }
            try {
                context.startForegroundService(
                    Intent(context, LocationForegroundService::class.java),
                )
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Foreground start refused; will retry on the next trigger", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationForegroundService::class.java))
        }
    }
}

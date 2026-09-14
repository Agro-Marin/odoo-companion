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
import com.odoocompanion.net.LocationFix
import com.odoocompanion.sync.SyncScheduler
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
                val settings = app.config.current()
                val due = queue.record(fixes, settings.uploadWindowSeconds, settings.minMoveMetres)
                if (due) SyncScheduler.uploadNow(applicationContext, settings.wifiOnlyUploads)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: SecurityException) {
            Log.w(TAG, "Foreground start refused; needs background location", e)
            stopSelf()
            return
        }
        if (!canRun(this)) {
            Log.w(TAG, "Location permission revoked since start was requested; stopping")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lifecycleScope.launch {
            val settings = CompanionApp.from(applicationContext).config.current()
            requestUpdates(settings.locationIntervalSeconds)
        }
        return START_STICKY
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
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission not granted; stopping reporting", e)
            stopSelf()
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

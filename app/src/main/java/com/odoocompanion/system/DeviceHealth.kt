package com.odoocompanion.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import androidx.core.content.ContextCompat

enum class Blocker {
    LOCATION_MISSING,

    BACKGROUND_LOCATION_MISSING,

    BATTERY_OPTIMIZED,

    CALL_LOG_PERMISSION_MISSING,

    RECORDING_STORAGE_MISSING,
}

data class HealthReport(
    val locationGranted: Boolean,
    val backgroundLocationGranted: Boolean,
    val callLogGranted: Boolean,
    val recordingStorageGranted: Boolean,
    val exemptFromBatteryOptimization: Boolean,
) {
    fun blockers(callLogWanted: Boolean, recordingsWanted: Boolean): List<Blocker> = buildList {
        if (!locationGranted) {
            add(Blocker.LOCATION_MISSING)
        } else if (!backgroundLocationGranted) {
            add(Blocker.BACKGROUND_LOCATION_MISSING)
        }
        if (!exemptFromBatteryOptimization) add(Blocker.BATTERY_OPTIMIZED)
        if (callLogWanted && !callLogGranted) add(Blocker.CALL_LOG_PERMISSION_MISSING)
        if (recordingsWanted && !recordingStorageGranted) {
            add(Blocker.RECORDING_STORAGE_MISSING)
        }
    }
}

object DeviceHealth {
    fun report(context: Context): HealthReport = HealthReport(
        locationGranted = granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(context, Manifest.permission.ACCESS_COARSE_LOCATION),
        backgroundLocationGranted = granted(
            context,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ),
        callLogGranted = granted(context, Manifest.permission.READ_CALL_LOG),
        recordingStorageGranted = hasRecordingStorageAccess(context),
        exemptFromBatteryOptimization = isExemptFromBatteryOptimization(context),
    )

    fun hasRecordingStorageAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        } else {
            granted(context, Manifest.permission.READ_EXTERNAL_STORAGE) &&
                runCatching { Environment.isExternalStorageLegacy() }.getOrDefault(false)
        }

    fun isExemptFromBatteryOptimization(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

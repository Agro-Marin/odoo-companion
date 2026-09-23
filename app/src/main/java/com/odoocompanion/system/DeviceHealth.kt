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

    // What to ask for, from the same two switches blockers() reads, so a
    // collector that is off never costs the person a prompt. Storage is the
    // legacy pair: from Android 11 recordings need all-files access instead,
    // which is a settings screen, not a runtime permission.
    fun wantedPermissions(
        callLogWanted: Boolean,
        recordingsWanted: Boolean,
        sdk: Int = Build.VERSION.SDK_INT,
    ): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (callLogWanted) add(Manifest.permission.READ_CALL_LOG)
        if (sdk >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        if (recordingsWanted && sdk < Build.VERSION_CODES.R) {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    fun hasRecordingStorageAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        } else {
            granted(context, Manifest.permission.READ_EXTERNAL_STORAGE) &&
                granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) &&
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

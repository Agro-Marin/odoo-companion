package com.odoocompanion.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.odoocompanion.CompanionApp
import com.odoocompanion.config.Settings
import com.odoocompanion.system.DeviceHealth
import com.odoocompanion.whatsapp.WhatsAppNotificationListener
import kotlinx.coroutines.launch

// Every grant the app asks for, for Home's "Grant permissions" and for what
// Settings asks after a save and from the WhatsApp card. Built in the
// activity's construction, since its launchers have to be registered before
// the activity starts. onChanged runs once a grant may have changed what the
// screen shows.
class Grants(private val activity: AppCompatActivity, private val onChanged: () -> Unit) {
    private val app get() = CompanionApp.from(activity)

    private val permissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val health = DeviceHealth.report(activity)
            if (health.locationGranted && !health.backgroundLocationGranted) {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                startIfEnrolled()
            }
        }

    private val backgroundLocationLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            startIfEnrolled()
        }

    private val contactsLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            onChanged()
        }

    // The gear's "Grant permissions": the runtime grants, then the two that
    // live on a settings screen of their own.
    fun requestAll() {
        activity.lifecycleScope.launch {
            val settings = app.config.current()
            requestPermissions(settings, app.softphones.current() != null)
            requestBatteryExemption()
            if (settings.recordingsEnabled) requestRecordingStorageAccess()
        }
    }

    fun requestPermissions(settings: Settings, softphoneWanted: Boolean) {
        val wanted = DeviceHealth.wantedPermissions(
            callLogWanted = settings.callLogEnabled,
            recordingsWanted = settings.recordingsEnabled,
            softphoneWanted = softphoneWanted,
        )
        permissionLauncher.launch(wanted.toTypedArray())
    }

    fun requestContacts() = contactsLauncher.launch(Manifest.permission.READ_CONTACTS)

    @SuppressLint("BatteryLife")
    fun requestBatteryExemption() {
        if (DeviceHealth.isExemptFromBatteryOptimization(activity)) return
        open(
            Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                "package:${activity.packageName}".toUri(),
            ),
            "battery optimization",
        )
    }

    fun requestRecordingStorageAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (DeviceHealth.hasRecordingStorageAccess(activity)) return
        open(
            Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                "package:${activity.packageName}".toUri(),
            ),
            "all-files access",
        )
    }

    // Straight to this app's own switch where Android has that screen (11+),
    // otherwise to the list of every app with notification access.
    fun requestNotificationAccess() {
        val list = Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(
                    android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    WhatsAppNotificationListener.component(activity).flattenToString(),
                )
        } else {
            list
        }
        if (!open(intent, "per-listener notification access")) {
            open(list, "notification access")
        }
    }

    private fun open(intent: Intent, screen: String): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No $screen settings screen on this device", e)
        false
    }

    private fun startIfEnrolled() {
        activity.lifecycleScope.launch {
            app.applyConfiguration()
            onChanged()
        }
    }

    private companion object {
        const val TAG = "Grants"
    }
}

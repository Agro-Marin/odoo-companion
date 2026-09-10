package com.odoocompanion.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.odoocompanion.CompanionApp
import com.odoocompanion.R
import com.odoocompanion.config.EnrollmentResult
import com.odoocompanion.config.Settings
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.databinding.ActivityMainBinding
import com.odoocompanion.location.LocationForegroundService
import com.odoocompanion.sync.SyncScheduler
import com.odoocompanion.system.DeviceHealth
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->

            if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true &&
                !DeviceHealth.report(this).backgroundLocationGranted
            ) {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                startIfEnrolled()
            }
        }

    private val backgroundLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startIfEnrolled() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = CompanionApp.from(this)

        var populated = false
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.config.settings.distinctUntilChanged().collect { settings ->
                    if (!populated || settings.managed) {
                        showForm(settings)
                        populated = true
                    }
                    render(settings)
                }
            }
        }

        binding.save.setOnClickListener {
            lifecycleScope.launch {
                app.config.setFeature(
                    callLog = binding.callLogEnabled.isChecked,
                    recordings = binding.recordingsEnabled.isChecked,
                )
                app.config.setWifiOnlyUploads(binding.wifiOnly.isChecked)
                binding.uploadWindow.text.toString().trim().toLongOrNull()
                    ?.let { app.config.setUploadWindow(it) }
                binding.locationInterval.text.toString().trim().toLongOrNull()
                    ?.let { app.config.setLocationInterval(it) }
                when (
                    app.config.saveEnrollment(
                        baseUrl = binding.baseUrl.text.toString(),
                        identifier = binding.identifier.text.toString(),
                        token = binding.token.text.toString(),
                    )
                ) {
                    EnrollmentResult.InvalidBaseUrl -> {
                        binding.status.text = getString(R.string.status_bad_base_url)
                        return@launch
                    }

                    EnrollmentResult.InvalidIdentifier -> {
                        binding.status.text = getString(R.string.status_bad_identifier)
                        return@launch
                    }

                    EnrollmentResult.CleartextRefused -> {
                        binding.status.text = getString(R.string.status_cleartext_refused)
                        return@launch
                    }

                    EnrollmentResult.Saved -> Unit
                }
                app.applyConfiguration()
                val settings = app.config.current()
                if (settings.isEnrolled && !LocationForegroundService.canRun(this@MainActivity)) {
                    requestPermissions(settings)
                }

                binding.locationInterval.showSeconds(settings.locationIntervalSeconds)
                binding.uploadWindow.showSeconds(settings.uploadWindowSeconds)
                render(settings)
            }
        }

        binding.syncNow.setOnClickListener {
            lifecycleScope.launch {
                SyncScheduler.collectAndUploadNow(
                    this@MainActivity,
                    app.config.current().wifiOnlyUploads,
                )
            }
        }

        binding.grantPermissions.setOnClickListener {
            lifecycleScope.launch {
                val settings = app.config.current()
                requestPermissions(settings)
                requestBatteryExemption()
                if (settings.recordingsEnabled) requestRecordingStorageAccess()
            }
        }
    }

    private fun requestPermissions(settings: Settings) {
        val wanted = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (settings.callLogEnabled) wanted += Manifest.permission.READ_CALL_LOG
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        } else {
            wanted += Manifest.permission.READ_EXTERNAL_STORAGE
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                wanted += Manifest.permission.WRITE_EXTERNAL_STORAGE
            }
        }
        permissionLauncher.launch(wanted.toTypedArray())
    }

    // Both fields are read back with toLongOrNull(), so the digits have to stay
    // machine-parseable: a locale-formatted number is what SetTextI18n asks for
    // and is exactly what would fail to parse on a handset with non-Latin digits.
    @SuppressLint("SetTextI18n")
    private fun EditText.showSeconds(value: Long) = setText(value.toString())

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        if (DeviceHealth.isExemptFromBatteryOptimization(this)) return
        try {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    "package:$packageName".toUri(),
                ),
            )
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No battery optimization settings screen on this device", e)
        }
    }

    private fun requestRecordingStorageAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (DeviceHealth.hasRecordingStorageAccess(this)) return
        try {
            startActivity(
                Intent(
                    android.provider.Settings
                        .ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    "package:$packageName".toUri(),
                ),
            )
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No all-files-access settings screen on this device", e)
        }
    }

    private fun startIfEnrolled() {
        lifecycleScope.launch {
            val app = CompanionApp.from(this@MainActivity)
            app.applyConfiguration()
            render(app.config.current())
        }
    }

    private fun showForm(settings: Settings) {
        binding.baseUrl.setText(settings.baseUrl)
        binding.identifier.setText(settings.identifier)
        binding.token.setText(settings.token)
        binding.callLogEnabled.isChecked = settings.callLogEnabled
        binding.recordingsEnabled.isChecked = settings.recordingsEnabled
        binding.wifiOnly.isChecked = settings.wifiOnlyUploads
        binding.locationInterval.showSeconds(settings.locationIntervalSeconds)
        binding.uploadWindow.showSeconds(settings.uploadWindowSeconds)
        applyManagedLock(settings.managed)
    }

    private fun applyManagedLock(managed: Boolean) {
        for (field in listOf(binding.baseUrl, binding.identifier, binding.token)) {
            field.isEnabled = !managed
        }
        binding.callLogEnabled.isEnabled = !managed
        binding.recordingsEnabled.isEnabled = !managed
        binding.wifiOnly.isEnabled = !managed
        binding.locationInterval.isEnabled = !managed
        binding.uploadWindow.isEnabled = !managed
        binding.save.isEnabled = !managed
    }

    private fun format(line: StatusLine): String = when (line) {
        is StatusLine.Say -> getString(line.text)

        is StatusLine.Count -> getString(line.text, line.value)

        is StatusLine.Quantity ->
            resources.getQuantityString(line.text, line.value, line.value)

        is StatusLine.Detail -> getString(line.text, line.detail)

        is StatusLine.Since ->
            getString(line.text, DateUtils.getRelativeTimeSpanString(line.at))
    }

    private suspend fun render(settings: Settings) {
        val outbox = CompanionApp.from(this).database.outbox()
        applyManagedLock(settings.managed)
        val queues = QueueDepths(
            positions = outbox.countOf(OutboxKind.LOCATION),
            calls = outbox.countOf(OutboxKind.CALL_LOG),
            recordings = outbox.countOf(OutboxKind.RECORDING),
            undeliverable = outbox.countDead(),
        )
        binding.status.text = StatusScreen
            .lines(settings, DeviceHealth.report(this), queues)
            .joinToString(separator = "\n", postfix = "\n", transform = ::format)
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}

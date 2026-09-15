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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
        keepContentOutFromUnderTheSystemBars()

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

    // targetSdk 36 means Android draws this window edge to edge and does not
    // ask: without this the first line of the status text sits under the clock
    // and the battery icon, and that line is the one that says whether the
    // phone is enrolled -- the first thing anyone reads when a phone stopped
    // reporting.
    private fun keepContentOutFromUnderTheSystemBars() {
        val root = binding.root
        // Captured once. The listener runs again on rotation and whenever the
        // keyboard opens, and padding added to whatever is currently set would
        // grow a little further each time.
        val base = listOf(
            root.paddingLeft,
            root.paddingTop,
            root.paddingRight,
            root.paddingBottom,
        )
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(
                base[0] + bars.left,
                base[1] + bars.top,
                base[2] + bars.right,
                // The keyboard is taller than the navigation bar it covers, so
                // the larger of the two is what keeps Save reachable while a
                // field is being typed into.
                base[3] + maxOf(bars.bottom, keyboard.bottom),
            )
            insets
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
        applyManagedLock(settings)
    }

    // A field is locked when the policy is managing THAT field, not merely
    // when a policy exists. A console that publishes the declared defaults of
    // app_restrictions.xml -- and several do, whether or not an administrator
    // filled anything in -- sends the five non-text keys and none of the three
    // that enrol. Locking the whole form on that leaves a phone nobody can
    // configure: not the MDM, which never supplied an identity, and not the
    // person holding it, whose fields are greyed out. A key that arrived and
    // turned out unusable is still managed, because the administrator is
    // plainly setting it; that is why this reads the keys the bundle carried
    // rather than the values that survived parsing.
    private fun applyManagedLock(settings: Settings) {
        val keys = settings.managedKeys.takeIf { settings.managed }.orEmpty()
        // Both halves of each field: disabling only the inner edit text leaves
        // the box and its label drawn as though they were still editable.
        val governed = listOf(
            "base_url" to listOf(binding.baseUrl, binding.baseUrlLayout),
            "identifier" to listOf(binding.identifier, binding.identifierLayout),
            "token" to listOf(binding.token, binding.tokenLayout),
            "location_interval_seconds" to
                listOf(binding.locationInterval, binding.locationIntervalLayout),
            "upload_window_seconds" to listOf(binding.uploadWindow, binding.uploadWindowLayout),
            "call_log_enabled" to listOf(binding.callLogEnabled),
            "recordings_enabled" to listOf(binding.recordingsEnabled),
            "wifi_only_uploads" to listOf(binding.wifiOnly),
        )
        for ((key, views) in governed) {
            views.forEach { it.isEnabled = key !in keys }
        }
        // Save writes every field at once, so it belongs to the form rather
        // than to any one key: it goes only when there is nothing left to save.
        binding.save.isEnabled = governed.any { (key, _) -> key !in keys }
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
        applyManagedLock(settings)
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

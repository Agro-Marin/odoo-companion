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
import com.odoocompanion.config.FormValues
import com.odoocompanion.config.ManagedConfig
import com.odoocompanion.config.Settings
import com.odoocompanion.data.OutboxCounts
import com.odoocompanion.databinding.ActivityMainBinding
import com.odoocompanion.location.LocationForegroundService
import com.odoocompanion.sync.SyncScheduler
import com.odoocompanion.system.DeviceHealth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val health = DeviceHealth.report(this)
            if (health.locationGranted && !health.backgroundLocationGranted) {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                startIfEnrolled()
            }
        }

    private val healthChecks = MutableStateFlow(0)

    private val refusal = MutableStateFlow<Int?>(null)

    override fun onResume() {
        super.onResume()
        healthChecks.value++
    }

    private val backgroundLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startIfEnrolled() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        keepContentOutFromUnderTheSystemBars()

        val app = CompanionApp.from(this)

        // Every field once, then only the ones a policy governs: the store
        // re-emits on every write -- each upload records its attempt -- and
        // repainting the whole form put the stored values back over whatever
        // was being typed into the fields the policy leaves to the user.
        var populated = false
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.config.settings.distinctUntilChanged().collect { settings ->
                        showForm(settings, if (populated) settings.governedKeys else FORM_KEYS)
                        populated = true
                    }
                }
                // Redrawn from what it shows: the settings, the queue -- which
                // changes on every fix and every upload without touching a
                // setting -- a refused save, and the permissions, which change
                // behind a dialog that only pauses this screen.
                combine(
                    app.config.settings,
                    app.database.outbox().counts(),
                    refusal,
                    healthChecks,
                ) { settings, counts, refused, _ -> statusText(settings, counts, refused) }
                    .distinctUntilChanged()
                    .collect { binding.status.text = it }
            }
        }

        binding.save.setOnClickListener {
            val form = formValues()
            lifecycleScope.launch {
                refusal.value = when (app.config.saveForm(form)) {
                    EnrollmentResult.InvalidBaseUrl -> R.string.status_bad_base_url
                    EnrollmentResult.InvalidIdentifier -> R.string.status_bad_identifier
                    EnrollmentResult.CleartextRefused -> R.string.status_cleartext_refused
                    EnrollmentResult.Saved -> null
                }
                if (refusal.value != null) return@launch
                app.applyConfiguration()
                val settings = app.config.current()
                if (settings.isEnrolled && !LocationForegroundService.canRun(this@MainActivity)) {
                    requestPermissions(settings)
                }

                binding.locationInterval.showSeconds(settings.locationIntervalSeconds)
                binding.uploadWindow.showSeconds(settings.uploadWindowSeconds)
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
        val wanted = DeviceHealth.wantedPermissions(
            callLogWanted = settings.callLogEnabled,
            recordingsWanted = settings.recordingsEnabled,
        )
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
            healthChecks.value++
        }
    }

    private fun formValues() = FormValues(
        baseUrl = binding.baseUrl.text.toString(),
        identifier = binding.identifier.text.toString(),
        token = binding.token.text.toString(),
        callLogEnabled = binding.callLogEnabled.isChecked,
        recordingsEnabled = binding.recordingsEnabled.isChecked,
        wifiOnlyUploads = binding.wifiOnly.isChecked,
        locationIntervalSeconds = binding.locationInterval.text.toString().trim().toLongOrNull(),
        uploadWindowSeconds = binding.uploadWindow.text.toString().trim().toLongOrNull(),
    )

    private fun showForm(settings: Settings, keys: Set<String>) {
        fun paint(key: String, show: () -> Unit) {
            if (key in keys) show()
        }
        paint("base_url") { binding.baseUrl.setText(settings.baseUrl) }
        paint("identifier") { binding.identifier.setText(settings.identifier) }
        paint("token") { binding.token.setText(settings.token) }
        paint("call_log_enabled") { binding.callLogEnabled.isChecked = settings.callLogEnabled }
        paint("recordings_enabled") {
            binding.recordingsEnabled.isChecked = settings.recordingsEnabled
        }
        paint("wifi_only_uploads") { binding.wifiOnly.isChecked = settings.wifiOnlyUploads }
        paint("location_interval_seconds") {
            binding.locationInterval.showSeconds(settings.locationIntervalSeconds)
        }
        paint("upload_window_seconds") {
            binding.uploadWindow.showSeconds(settings.uploadWindowSeconds)
        }
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
        val keys = settings.governedKeys
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

    private fun statusText(settings: Settings, counts: OutboxCounts, refused: Int?): String = (
        listOfNotNull(refused?.let(::getString)) +
            StatusScreen.lines(settings, DeviceHealth.report(this), counts).map(::format)
        )
        .joinToString(separator = "\n", postfix = "\n")

    private companion object {
        const val TAG = "MainActivity"

        val FORM_KEYS = ManagedConfig.MANAGED_KEYS
    }
}

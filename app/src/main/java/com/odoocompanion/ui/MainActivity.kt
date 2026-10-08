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
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.odoocompanion.CompanionApp
import com.odoocompanion.R
import com.odoocompanion.config.EnrollmentResult
import com.odoocompanion.config.FormValues
import com.odoocompanion.config.ManagedKey
import com.odoocompanion.config.Settings
import com.odoocompanion.data.OutboxCounts
import com.odoocompanion.databinding.ActivityMainBinding
import com.odoocompanion.location.LocationForegroundService
import com.odoocompanion.sync.SyncScheduler
import com.odoocompanion.system.DeviceHealth
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
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

    private lateinit var guide: EnrollmentGuideView

    // The steps the person closed with "Got it": each comes back once its
    // field has been filled in and emptied again.
    private val dismissedSteps = mutableSetOf<GuideField>()

    private var governedKeys: Set<String> = emptySet()

    // "Last upload: 2 minutes ago" is how long the phone has been silent, and an
    // idle phone writes nothing to the store that would redraw it: without a
    // tick it said "2 minutes ago" for as long as the screen stayed open.
    private val minutes = flow {
        while (true) {
            emit(Unit)
            delay(STATUS_TICK_MILLIS)
        }
    }

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
        setUpGuide()

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
                // setting -- a refused save, the softphone, and two plain
                // triggers: the permissions, which change behind a dialog that
                // only pauses this screen, and the minute, which ages "Last
                // upload: 2 minutes ago" on a phone nothing else redraws.
                combine(
                    app.config.settings,
                    app.database.outbox().counts(),
                    refusal,
                    merge(healthChecks, minutes),
                    app.softphones.configured,
                ) { settings, counts, refused, _, softphone ->
                    statusText(settings, counts, refused, softphone)
                }
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
                val softphone = app.softphones.current() != null
                val missing = !LocationForegroundService.canRun(this@MainActivity) ||
                    (softphone && !DeviceHealth.report(this@MainActivity).microphoneGranted)
                if (settings.isEnrolled && missing) requestPermissions(settings, softphone)

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
                requestPermissions(settings, app.softphones.current() != null)
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

    private fun requestPermissions(settings: Settings, softphoneWanted: Boolean) {
        val wanted = DeviceHealth.wantedPermissions(
            callLogWanted = settings.callLogEnabled,
            recordingsWanted = settings.recordingsEnabled,
            softphoneWanted = softphoneWanted,
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
        paint(ManagedKey.BASE_URL) { binding.baseUrl.setText(settings.baseUrl) }
        paint(ManagedKey.IDENTIFIER) { binding.identifier.setText(settings.identifier) }
        paint(ManagedKey.TOKEN) { binding.token.setText(settings.token) }
        paint(ManagedKey.CALL_LOG_ENABLED) {
            binding.callLogEnabled.isChecked = settings.callLogEnabled
        }
        paint(ManagedKey.RECORDINGS_ENABLED) {
            binding.recordingsEnabled.isChecked = settings.recordingsEnabled
        }
        paint(ManagedKey.WIFI_ONLY_UPLOADS) {
            binding.wifiOnly.isChecked = settings.wifiOnlyUploads
        }
        paint(ManagedKey.LOCATION_INTERVAL_SECONDS) {
            binding.locationInterval.showSeconds(settings.locationIntervalSeconds)
        }
        paint(ManagedKey.UPLOAD_WINDOW_SECONDS) {
            binding.uploadWindow.showSeconds(settings.uploadWindowSeconds)
        }
        applyManagedLock(settings)
        governedKeys = settings.governedKeys
        refreshGuide()
    }

    private fun setUpGuide() {
        guide = EnrollmentGuideView(
            steps = mapOf(
                GuideField.BASE_URL to (binding.baseUrlLayout to binding.guideBaseUrl),
                GuideField.IDENTIFIER to (binding.identifierLayout to binding.guideIdentifier),
                GuideField.TOKEN to (binding.tokenLayout to binding.guideToken),
            ),
            onDismiss = { field ->
                dismissedSteps += field
                refreshGuide()
            },
        )
        binding.identifier.filters += IdentifierInputFilter {
            binding.identifierLayout.helperText = getString(R.string.identifier_no_spaces)
        }
        listOf(binding.baseUrl, binding.identifier, binding.token).forEach { field ->
            field.doAfterTextChanged { refreshGuide() }
        }
    }

    private fun refreshGuide() {
        val values = mapOf(
            GuideField.BASE_URL to binding.baseUrl.text.toString(),
            GuideField.IDENTIFIER to binding.identifier.text.toString(),
            GuideField.TOKEN to binding.token.text.toString(),
        )
        dismissedSteps.removeAll { !values[it].isNullOrBlank() }
        guide.show(EnrollmentGuide.current(values, governedKeys, dismissedSteps))
    }

    override fun onDestroy() {
        if (::guide.isInitialized) guide.stop()
        super.onDestroy()
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
            ManagedKey.BASE_URL to listOf(binding.baseUrl, binding.baseUrlLayout),
            ManagedKey.IDENTIFIER to listOf(binding.identifier, binding.identifierLayout),
            ManagedKey.TOKEN to listOf(binding.token, binding.tokenLayout),
            ManagedKey.LOCATION_INTERVAL_SECONDS to
                listOf(binding.locationInterval, binding.locationIntervalLayout),
            ManagedKey.UPLOAD_WINDOW_SECONDS to
                listOf(binding.uploadWindow, binding.uploadWindowLayout),
            ManagedKey.CALL_LOG_ENABLED to listOf(binding.callLogEnabled),
            ManagedKey.RECORDINGS_ENABLED to listOf(binding.recordingsEnabled),
            ManagedKey.WIFI_ONLY_UPLOADS to listOf(binding.wifiOnly),
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

    private fun statusText(
        settings: Settings,
        counts: OutboxCounts,
        refused: Int?,
        softphoneWanted: Boolean,
    ): String = (
        listOfNotNull(refused?.let(::getString)) +
            StatusScreen.lines(settings, DeviceHealth.report(this), counts, softphoneWanted)
                .map(::format)
        )
        .joinToString(separator = "\n", postfix = "\n")

    private companion object {
        const val TAG = "MainActivity"

        const val STATUS_TICK_MILLIS = 60_000L

        val FORM_KEYS = ManagedKey.ALL
    }
}

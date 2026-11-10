package com.odoocompanion.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.Log
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.odoocompanion.CompanionApp
import com.odoocompanion.R
import com.odoocompanion.config.EnrollmentResult
import com.odoocompanion.config.FormValues
import com.odoocompanion.config.ManagedKey
import com.odoocompanion.config.Settings
import com.odoocompanion.data.OutboxCounts
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.data.QueuedFileState
import com.odoocompanion.databinding.ActivityMainBinding
import com.odoocompanion.location.LocationForegroundService
import com.odoocompanion.sync.SyncScheduler
import com.odoocompanion.system.DeviceHealth
import com.odoocompanion.system.LocalNetwork
import com.odoocompanion.whatsapp.ContactBook
import com.odoocompanion.whatsapp.DocumentKind
import com.odoocompanion.whatsapp.Sighting
import com.odoocompanion.whatsapp.UploadState
import com.odoocompanion.whatsapp.WhatsAppIntake
import com.odoocompanion.whatsapp.WhatsAppNotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private val dismissedSteps = mutableSetOf<GuideField>()

    private var governedKeys: Set<String> = emptySet()

    private var shownAlerts: List<String> = emptyList()

    private var localNetworkShown = false

    private var shownHeld: HeldQueue? = null

    private var alertsUnfolded = false

    private val minutes = flow {
        while (true) {
            emit(Unit)
            delay(STATUS_TICK_MILLIS)
        }
    }

    private var whatsappMissing: WhatsAppGrant? = null

    private var documentQueue: Map<String, QueuedFileState> = emptyMap()

    override fun onResume() {
        super.onResume()
        healthChecks.value++
        val whatsapp = CompanionApp.from(this).whatsapp
        val contacts = ContactBook(this)
        WhatsAppNotificationListener.reconnect(this)
        lifecycleScope.launch(Dispatchers.IO) {
            whatsapp.rematch()
            if (DeviceHealth.hasRecordingStorageAccess(this@MainActivity)) {
                whatsapp.adoptLooseFiles()
            }
            if (contacts.granted) whatsapp.fillNumbers(contacts::numberOf)
            WhatsAppIntake.process(this@MainActivity)
            delay(REBIND_GRACE_MILLIS)
            healthChecks.value++
        }
    }

    private val contactsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
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
        setUpActions()

        var populated = false
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.config.settings.distinctUntilChanged().collect { settings ->
                        showForm(settings, if (populated) settings.governedKeys else FORM_KEYS)
                        populated = true
                    }
                }
                combine(
                    app.config.settings,
                    queueCounts(),
                    refusal,
                    merge(healthChecks, minutes),
                    combine(app.softphones.configured, localNetworkWanted(app), ::Pair),
                ) { settings, counts, refused, _, (softphone, localNetwork) ->
                    localNetworkShown = localNetwork
                    panelText(settings, counts, refused, softphone, localNetwork)
                }
                    .distinctUntilChanged()
                    .collect(::showPanel)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch(Dispatchers.IO) { app.whatsapp.open() }
                combine(
                    app.whatsapp.sightings,
                    app.database.outbox().fileStates(OutboxKind.DOCUMENT),
                    merge(healthChecks, minutes),
                ) { sightings, queue, _ ->
                    documentQueue = queue.associateBy { it.filePath }
                    WhatsAppPanel.card(
                        listenerOn = WhatsAppNotificationListener.isEnabled(this@MainActivity),
                        listenerConnected = WhatsAppNotificationListener.connected,
                        storageOn = DeviceHealth.hasRecordingStorageAccess(this@MainActivity),
                        contactsOn = ContactBook(this@MainActivity).granted,
                        sightings = sightings.orEmpty(),
                    )
                }.collect(::showWhatsApp)
            }
        }
        binding.whatsappEnable.setOnClickListener {
            when (whatsappMissing) {
                WhatsAppGrant.NOTIFICATIONS, WhatsAppGrant.DISCONNECTED ->
                    requestNotificationAccess()

                WhatsAppGrant.ALL_FILES -> requestRecordingStorageAccess()

                WhatsAppGrant.CONTACTS ->
                    contactsLauncher.launch(Manifest.permission.READ_CONTACTS)

                null -> Unit
            }
        }

        binding.save.setOnClickListener {
            showActions(false)
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
                val localNetwork = localNetworkWanted(app, settings)
                val health = DeviceHealth.report(this@MainActivity)
                val missing = !LocationForegroundService.canRun(this@MainActivity) ||
                    (softphone && !health.microphoneGranted) ||
                    (localNetwork && !health.localNetworkGranted)
                if (settings.isEnrolled && missing) {
                    requestPermissions(settings, softphone, localNetwork)
                }

                binding.locationInterval.showSeconds(settings.locationIntervalSeconds)
                binding.uploadWindow.showSeconds(settings.uploadWindowSeconds)
            }
        }

        binding.syncNow.setOnClickListener {
            showActions(false)
            lifecycleScope.launch {
                SyncScheduler.collectAndUploadNow(
                    this@MainActivity,
                    app.config.current().wifiOnlyUploads,
                )
            }
        }

        binding.heldSend.setOnClickListener { confirmHeld(send = true) }
        binding.heldDiscard.setOnClickListener { confirmHeld(send = false) }

        binding.grantPermissions.setOnClickListener {
            showActions(false)
            lifecycleScope.launch {
                val settings = app.config.current()
                requestPermissions(
                    settings,
                    softphoneWanted = app.softphones.current() != null,
                    localNetworkWanted = localNetworkShown,
                )
                requestBatteryExemption()
                if (settings.recordingsEnabled) requestRecordingStorageAccess()
            }
        }
    }

    private fun keepContentOutFromUnderTheSystemBars() {
        val scroll = binding.formScroll
        val menuOffset =
            (binding.actionsMenu.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        val base = listOf(
            scroll.paddingLeft,
            scroll.paddingTop,
            scroll.paddingRight,
            scroll.paddingBottom,
        )
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = maxOf(bars.bottom, keyboard.bottom)
            scroll.setPadding(
                base[0] + bars.left,
                base[1] + bars.top,
                base[2] + bars.right,
                base[3] + bottom,
            )
            binding.guideDock.updatePadding(bottom = bottom)
            binding.actionsMenu.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = bars.top + menuOffset
            }
            binding.guideDock.isVisible = !insets.isVisible(WindowInsetsCompat.Type.ime())
            insets
        }
    }

    private fun requestPermissions(
        settings: Settings,
        softphoneWanted: Boolean,
        localNetworkWanted: Boolean,
    ) {
        val wanted = DeviceHealth.wantedPermissions(
            callLogWanted = settings.callLogEnabled,
            recordingsWanted = settings.recordingsEnabled,
            softphoneWanted = softphoneWanted,
            localNetworkWanted = localNetworkWanted,
        )
        permissionLauncher.launch(wanted.toTypedArray())
    }

    private suspend fun localNetworkWanted(app: CompanionApp, settings: Settings): Boolean =
        withContext(Dispatchers.IO) {
            LocalNetwork.anyLocal(
                settings.baseUrl.takeIf { settings.isEnrolled },
                app.softphones.current()?.domain,
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun localNetworkWanted(app: CompanionApp): Flow<Boolean> = combine(
        app.config.settings.map { it.baseUrl.takeIf { _ -> it.isEnrolled } },
        app.softphones.domain,
        healthChecks,
    ) { baseUrl, domain, _ -> baseUrl to domain }
        .mapLatest { (baseUrl, domain) -> LocalNetwork.anyLocal(baseUrl, domain) }
        .flowOn(Dispatchers.IO)
        .distinctUntilChanged()

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

    private fun requestNotificationAccess() {
        val list = Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(
                    android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    WhatsAppNotificationListener.component(this).flattenToString(),
                )
        } else {
            list
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No per-listener settings screen; opening the list", e)
            try {
                startActivity(list)
            } catch (missing: ActivityNotFoundException) {
                Log.w(TAG, "No notification access settings screen on this device", missing)
            }
        }
    }

    private fun showWhatsApp(card: WhatsAppCard) {
        whatsappMissing = card.missing
        binding.whatsappEnable.isVisible = card.missing != null
        binding.whatsappState.text = if (card.state == R.string.whatsapp_watching) {
            getString(card.state, card.seen, card.found)
        } else {
            getString(card.state)
        }
        binding.whatsappSightings.showOptional(
            card.shown.joinToString("\n\n", transform = ::sightingText).ifEmpty { null },
        )
    }

    private fun sightingText(sighting: Sighting): String {
        val who = when {
            sighting.loose -> getString(R.string.whatsapp_item_open_chat)
            sighting.isGroup -> "${sighting.sender} @ ${sighting.chat}"
            else -> sighting.chat
        }
        val file = if (sighting.found) {
            getString(
                R.string.whatsapp_item_found,
                Formatter.formatShortFileSize(this, sighting.fileSize),
            )
        } else {
            getString(R.string.whatsapp_item_waiting)
        }
        val phone = when {
            sighting.phone != null -> getString(R.string.whatsapp_item_phone, sighting.phone)
            sighting.loose -> getString(R.string.whatsapp_item_no_notice)
            else -> getString(R.string.whatsapp_item_no_phone)
        }
        val kind = sighting.classification?.let { read ->
            val name = getString(
                when (read.kind) {
                    DocumentKind.CSF -> R.string.whatsapp_kind_csf
                    DocumentKind.INE -> R.string.whatsapp_kind_ine
                    DocumentKind.PASSPORT -> R.string.whatsapp_kind_passport
                    DocumentKind.OTHER -> R.string.whatsapp_kind_other
                },
            )
            val upload = when (WhatsAppIntake.uploadStateOf(sighting, documentQueue)) {
                UploadState.WAITING -> R.string.whatsapp_upload_waiting
                UploadState.QUEUED -> R.string.whatsapp_upload_queued
                UploadState.DELIVERED -> R.string.whatsapp_upload_done
                UploadState.SET_ASIDE -> R.string.whatsapp_upload_failed
                UploadState.GONE -> R.string.whatsapp_upload_gone
                UploadState.WITHDRAWN -> R.string.whatsapp_upload_withdrawn
                null -> null
            }
            listOfNotNull(
                name,
                read.rfc?.let { getString(R.string.whatsapp_item_rfc, it) },
                upload?.let(::getString),
            ).joinToString(" · ")
        } ?: getString(
            when {
                sighting.unreadable -> R.string.whatsapp_kind_unreadable
                sighting.missing -> R.string.whatsapp_kind_unread
                sighting.found -> R.string.whatsapp_kind_pending
                else -> R.string.whatsapp_kind_unread
            },
        )
        return getString(
            R.string.whatsapp_item,
            kind,
            sighting.label,
            who,
            DateUtils.getRelativeTimeSpanString(minOf(sighting.at, System.currentTimeMillis())),
            file,
            phone,
        )
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
        binding.alerts.setOnClickListener {
            alertsUnfolded = !alertsUnfolded
            showAlerts(shownAlerts, binding.alertGrant.isVisible)
        }
        explainWhileEditing(binding.locationIntervalLayout, R.string.config_location_interval)
        explainWhileEditing(binding.uploadWindowLayout, R.string.config_upload_window)
    }

    private val closeActionsOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showActions(false)
    }

    private fun setUpActions() {
        onBackPressedDispatcher.addCallback(this, closeActionsOnBack)
        binding.actionsToggle.setOnClickListener {
            showActions(!binding.actionsMenu.isVisible)
        }
        binding.actionsScrim.setOnClickListener { showActions(false) }
        binding.alertGrant.setOnClickListener { binding.grantPermissions.performClick() }
    }

    private fun showActions(open: Boolean) {
        binding.actionsMenu.isVisible = open
        binding.actionsScrim.isVisible = open
        closeActionsOnBack.isEnabled = open
    }

    private fun explainWhileEditing(layout: TextInputLayout, @StringRes help: Int) {
        layout.editText?.setOnFocusChangeListener { _, focused ->
            layout.helperText = if (focused) getString(help) else null
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

    private fun applyManagedLock(settings: Settings) {
        val keys = settings.governedKeys
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

    private data class PanelText(
        val enrolled: Boolean,
        val managed: Boolean,
        val blocked: Boolean,
        val alerts: List<String>,
        val lastUpload: String,
        val lastAttempt: String?,
        val lastError: String?,
        val queues: OutboxCounts,
        val held: HeldQueue?,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun queueCounts() = CompanionApp.from(this).let { app ->
        app.config.settings
            .map { it.enrollment }
            .distinctUntilChanged()
            .flatMapLatest { app.database.outbox().counts(it) }
    }

    private fun confirmHeld(send: Boolean) {
        val held = shownHeld ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(if (send) R.string.held_send_title else R.string.held_discard_title)
            .setMessage(
                if (send) {
                    getString(R.string.held_send_message, held.collectedFor)
                } else {
                    getString(R.string.held_discard_message)
                },
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(
                if (send) R.string.action_held_send else R.string.action_held_discard,
            ) { _, _ ->
                val app = CompanionApp.from(this)
                lifecycleScope.launch(Dispatchers.IO) {
                    val settings = app.config.currentOrNull() ?: return@launch
                    val enrollment = settings.enrollment ?: return@launch
                    val outbox = app.database.outbox()
                    if (send) {
                        outbox.adoptHeld(enrollment, held.enrollment)
                        SyncScheduler.uploadAfresh(this@MainActivity, settings.wifiOnlyUploads)
                    } else {
                        outbox.deleteHeldPositions(enrollment, held.enrollment)
                        outbox.withdrawHeld(enrollment, held.enrollment, System.currentTimeMillis())
                    }
                }
            }
            .show()
    }

    private fun panelText(
        settings: Settings,
        counts: OutboxCounts,
        refused: Int?,
        softphoneWanted: Boolean,
        localNetworkWanted: Boolean,
    ): PanelText {
        val panel = StatusScreen.panel(
            settings,
            DeviceHealth.report(this),
            counts,
            softphoneWanted,
            localNetworkWanted,
        )
        return PanelText(
            enrolled = panel.enrolled,
            managed = panel.managed,
            blocked = panel.blocked,
            alerts = listOfNotNull(refused?.let(::getString)) + panel.alerts.map(::format),
            lastUpload = format(panel.lastUpload),
            lastAttempt = panel.lastAttempt?.let(::format),
            lastError = panel.lastError?.let(::format),
            queues = panel.queues,
            held = panel.held,
        )
    }

    private fun showPanel(panel: PanelText) {
        binding.stateText.setText(
            if (panel.enrolled) R.string.status_enrolled else R.string.status_not_enrolled,
        )
        val dot = if (panel.enrolled) R.color.brand_primary else R.color.alert_ink
        ImageViewCompat.setImageTintList(
            binding.stateDot,
            ColorStateList.valueOf(ContextCompat.getColor(this, dot)),
        )
        binding.managedBadge.isVisible = panel.managed
        showAlerts(panel.alerts, panel.blocked)
        binding.lastUpload.text = panel.lastUpload
        binding.lastAttempt.showOptional(panel.lastAttempt)
        binding.lastError.showOptional(panel.lastError)
        binding.queuePositions.showFigure(panel.queues.positions, R.string.status_queued_positions)
        binding.queueCalls.showFigure(panel.queues.calls, R.string.status_queued_calls)
        binding.queueRecordings.showFigure(
            panel.queues.recordings,
            R.string.status_queued_recordings,
        )
        binding.queueDocuments.showFigure(panel.queues.documents, R.string.status_queued_documents)
        shownHeld = panel.held
        binding.heldBand.isVisible = panel.held != null
        binding.heldText.text = panel.held?.let {
            resources.getQuantityString(R.plurals.status_held, it.rows, it.rows, it.collectedFor)
        }
    }

    private fun showAlerts(alerts: List<String>, blocked: Boolean) {
        shownAlerts = alerts
        binding.alerts.isVisible = alerts.isNotEmpty()
        binding.alertGrant.isVisible = blocked
        binding.alertText.contentDescription = alerts.joinToString("\n")
        binding.alertText.text = when {
            alerts.size <= 1 || alertsUnfolded -> alerts.joinToString("\n")
            else -> getString(R.string.alerts_more, alerts.first(), alerts.size - 1)
        }
    }

    private fun TextView.showOptional(value: String?) {
        text = value.orEmpty()
        isVisible = value != null
    }

    @SuppressLint("SetTextI18n")
    private fun TextView.showFigure(value: Int, @StringRes spoken: Int) {
        text = value.toString()
        contentDescription = getString(spoken, value)
    }

    private companion object {
        const val TAG = "MainActivity"

        const val STATUS_TICK_MILLIS = 60_000L

        const val REBIND_GRACE_MILLIS = 3_000L

        val FORM_KEYS = ManagedKey.ALL
    }
}

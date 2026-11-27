package com.odoocompanion.ui

import android.annotation.SuppressLint
import android.database.sqlite.SQLiteException
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.Log
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
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
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.data.QueuedFileState
import com.odoocompanion.databinding.ActivitySettingsBinding
import com.odoocompanion.location.LocationForegroundService
import com.odoocompanion.sync.OutboxDrainer
import com.odoocompanion.sync.SyncScheduler
import com.odoocompanion.system.DeviceHealth
import com.odoocompanion.system.debug
import com.odoocompanion.whatsapp.ContactBook
import com.odoocompanion.whatsapp.Sighting
import com.odoocompanion.whatsapp.WhatsAppIntake
import com.odoocompanion.whatsapp.WhatsAppNotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    private val healthChecks = MutableStateFlow(0)

    private val refusal = MutableStateFlow<Int?>(null)

    private val grants = Grants(this) { healthChecks.value++ }

    private lateinit var guide: EnrollmentGuideView

    private lateinit var alerts: AlertBand

    private val dismissedSteps = mutableSetOf<GuideField>()

    private var governedKeys: Set<String> = emptySet()

    private var whatsappMissing: WhatsAppGrant? = null

    private var documentQueue: Map<String, List<QueuedFileState>> = emptyMap()

    private var shownHeld: HeldQueue? = null

    override fun onResume() {
        super.onResume()
        healthChecks.value++
        val whatsapp = CompanionApp.from(this).whatsapp
        val contacts = ContactBook(this)
        WhatsAppNotificationListener.reconnect(this)
        lifecycleScope.launch(Dispatchers.IO) {
            whatsapp.rematch()
            if (DeviceHealth.hasRecordingStorageAccess(this@SettingsActivity)) {
                whatsapp.adoptLooseFiles()
            }
            if (contacts.granted) whatsapp.fillNumbers(contacts::numberOf)
            WhatsAppIntake.process(this@SettingsActivity)
            delay(REBIND_GRACE_MILLIS)
            healthChecks.value++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        keepContentOutFromUnderTheSystemBars()

        val app = CompanionApp.from(this)
        alerts = AlertBand(binding.alerts) { grants.requestAll() }
        setUpGuide()
        setUpAppearance()
        binding.back.setOnClickListener { finish() }
        binding.appVersion.text = versionText(packageManager.getPackageInfo(packageName, 0))

        var populated = false
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.config.settings.distinctUntilChanged().collect { settings ->
                        showForm(settings, if (populated) settings.governedKeys else FORM_KEYS)
                        populated = true
                    }
                }
                StatusText.panels(this@SettingsActivity, healthChecks, refusal).collect(::showPanel)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch(Dispatchers.IO) { app.whatsapp.open() }
                combine(
                    app.whatsapp.sightings,
                    app.database.outbox().fileStates(OutboxKind.DOCUMENT).distinctUntilChanged(),
                    merge(healthChecks, statusMinutes),
                ) { sightings, queue, _ ->
                    documentQueue = queue.groupBy { it.filePath }
                    WhatsAppPanel.card(
                        listenerOn = WhatsAppNotificationListener.isEnabled(this@SettingsActivity),
                        listenerConnected = WhatsAppNotificationListener.connected,
                        storageOn = DeviceHealth.hasRecordingStorageAccess(this@SettingsActivity),
                        contactsOn = ContactBook(this@SettingsActivity).granted,
                        sightings = sightings.orEmpty(),
                    )
                }.collect(::showWhatsApp)
            }
        }
        binding.whatsappEnable.setOnClickListener {
            when (whatsappMissing) {
                WhatsAppGrant.NOTIFICATIONS, WhatsAppGrant.DISCONNECTED ->
                    grants.requestNotificationAccess()

                WhatsAppGrant.ALL_FILES -> grants.requestRecordingStorageAccess()

                WhatsAppGrant.CONTACTS -> grants.requestContacts()

                null -> Unit
            }
        }

        binding.heldSend.setOnClickListener { confirmHeld(send = true) }
        binding.heldDiscard.setOnClickListener { confirmHeld(send = false) }

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
                val localNetwork = StatusText.localNetworkWanted(app, settings)
                val health = DeviceHealth.report(this@SettingsActivity)
                val missing = !LocationForegroundService.canRun(this@SettingsActivity) ||
                    (softphone && !health.microphoneGranted) ||
                    (localNetwork && !health.localNetworkGranted)
                if (settings.isEnrolled && missing) {
                    grants.requestPermissions(settings, softphone, localNetwork)
                }

                binding.locationInterval.showSeconds(settings.locationIntervalSeconds)
                binding.uploadWindow.showSeconds(settings.uploadWindowSeconds)
            }
        }
    }

    private fun keepContentOutFromUnderTheSystemBars() {
        val bar = binding.settingsBar
        val scroll = binding.formScroll
        val barTop = bar.paddingTop
        val scrollBottom = scroll.paddingBottom
        val sides = bar.paddingStart to scroll.paddingStart
        val dock = binding.guideDock
        var bottom = 0

        fun padScroll() = scroll.updatePadding(
            bottom = scrollBottom + if (dock.isVisible) dock.height else bottom,
        )
        dock.addOnLayoutChangeListener { _, _, top, _, end, _, oldTop, _, oldEnd ->
            if (end - top != oldEnd - oldTop) scroll.post { padScroll() }
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            bottom = maxOf(bars.bottom, keyboard.bottom)
            bar.updatePadding(top = barTop + bars.top, left = sides.first + bars.left)
            scroll.updatePadding(left = sides.second + bars.left, right = sides.second + bars.right)
            dock.updatePadding(bottom = bottom)
            dock.isVisible = !insets.isVisible(WindowInsetsCompat.Type.ime())
            padScroll()
            insets
        }
    }

    private fun setUpAppearance() {
        val buttons = mapOf(
            Appearance.SYSTEM to R.id.appearanceSystem,
            Appearance.LIGHT to R.id.appearanceLight,
            Appearance.DARK to R.id.appearanceDark,
        )
        binding.appearance.check(buttons.getValue(Appearance.current(this)))
        binding.appearance.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val chosen = buttons.entries.first { it.value == id }.key
            if (chosen != Appearance.current(this)) Appearance.choose(this, chosen)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun EditText.showSeconds(value: Long) = setText(value.toString())

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
            val name = getString(read.kind.labelResource)
            val upload = WhatsAppIntake.uploadStateOf(sighting, documentQueue)?.labelResource
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
        explainWhileEditing(binding.locationIntervalLayout, R.string.config_location_interval)
        explainWhileEditing(binding.uploadWindowLayout, R.string.config_upload_window)
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

    private fun showPanel(panel: PanelText) {
        binding.statePill.show(panel.enrolled)
        binding.managedBadge.isVisible = panel.managed
        alerts.show(panel.alerts, panel.blocked)
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
            resources.getQuantityString(
                R.plurals.status_held,
                it.rows,
                it.rows,
                StatusScreen.describe(it.from)
            )
        }
    }

    private fun confirmHeld(send: Boolean) {
        val held = shownHeld ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(if (send) R.string.held_send_title else R.string.held_discard_title)
            .setMessage(
                if (send) {
                    getString(
                        R.string.held_send_message,
                        StatusScreen.describe(held.from),
                        StatusScreen.describe(held.destination),
                    )
                } else {
                    getString(R.string.held_discard_message, StatusScreen.describe(held.from))
                },
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(
                if (send) R.string.action_held_send else R.string.action_held_discard,
            ) { _, _ ->
                lifecycleScope.launch { applyHeldDecision(held, send) }
            }
            .show()
    }

    private suspend fun applyHeldDecision(held: HeldQueue, send: Boolean) {
        val app = CompanionApp.from(this)
        val outbox = app.database.outbox()
        var adopted = 0
        val settings = try {
            OutboxDrainer.withQueueOwnership {
                app.config.withCurrentEnrollment(held.destination) {
                    if (send) {
                        adopted = outbox.adoptHeld(held.destination, held.from)
                    } else {
                        outbox.discardHeld(held.destination, held.from, System.currentTimeMillis())
                    }
                }
            }
        } catch (failure: SQLiteException) {
            Log.w(TAG, "Could not update held rows (${failure.javaClass.simpleName})")
            null
        }
        debug(TAG) { "Held queue confirmation applied=${settings != null}, adopted=$adopted" }
        if (settings == null) {
            Toast.makeText(this, R.string.held_action_retry, Toast.LENGTH_LONG).show()
        } else if (adopted > 0) {
            SyncScheduler.uploadAfresh(this, settings.wifiOnlyUploads)
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

    companion object {
        private const val TAG = "SettingsActivity"

        private const val REBIND_GRACE_MILLIS = 3_000L

        private val FORM_KEYS = ManagedKey.ALL
    }
}

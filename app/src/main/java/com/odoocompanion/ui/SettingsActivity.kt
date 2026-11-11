package com.odoocompanion.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.PackageInfoCompat
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
import com.odoocompanion.sync.SyncScheduler
import com.odoocompanion.system.DeviceHealth
import com.odoocompanion.whatsapp.ContactBook
import com.odoocompanion.whatsapp.DocumentKind
import com.odoocompanion.whatsapp.Sighting
import com.odoocompanion.whatsapp.UploadState
import com.odoocompanion.whatsapp.WhatsAppIntake
import com.odoocompanion.whatsapp.WhatsAppNotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

// Everything that configures the phone and reports on it: the status, the
// connection, the reporting cadence, what is sent, the appearance and the
// WhatsApp documents report. Home opens it from the gear.
class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    private val healthChecks = MutableStateFlow(0)

    private val refusal = MutableStateFlow<Int?>(null)

    private val grants = Grants(this) { healthChecks.value++ }

    private lateinit var guide: EnrollmentGuideView

    private lateinit var alerts: AlertBand

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

    // The grant the WhatsApp card's button opens, as the card last said.
    private var whatsappMissing: WhatsAppGrant? = null

    // Each document file in the outbox, and whether it was set aside.
    private var documentQueue: Map<String, QueuedFileState> = emptyMap()

    private var shownHeld: HeldQueue? = null

    override fun onResume() {
        super.onResume()
        healthChecks.value++
        // The screen does the listener's looking too, so what arrived is shown
        // whether or not the system kept the listener bound: a document
        // downloaded since its notice, and one that came with no notice at all.
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
            // A rebind takes a moment; the card asks again once it may be done.
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
        binding.appVersion.text = versionText()

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
                    StatusText.queueCounts(app),
                    refusal,
                    merge(healthChecks, minutes),
                    combine(
                        app.softphones.configured,
                        StatusText.localNetworkWanted(app, healthChecks),
                        ::Pair,
                    ),
                ) { settings, counts, refused, _, (softphone, localNetwork) ->
                    StatusText.panel(
                        this@SettingsActivity,
                        settings,
                        counts,
                        refused,
                        softphone,
                        localNetwork,
                    )
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

    // targetSdk 36 means Android draws this window edge to edge and does not
    // ask: the bar goes under the clock without this, and the form's last
    // switch under the navigation bar.
    private fun keepContentOutFromUnderTheSystemBars() {
        val bar = binding.settingsBar
        val scroll = binding.formScroll
        // Captured once. The listener runs again on rotation and whenever the
        // keyboard opens, and padding added to whatever is currently set would
        // grow a little further each time.
        val barTop = bar.paddingTop
        val scrollBottom = scroll.paddingBottom
        val sides = bar.paddingStart to scroll.paddingStart
        val dock = binding.guideDock
        var bottom = 0

        // The guide floats over the end of the form; the form ends that much
        // higher while it is shown, or the last sections could not be reached.
        fun padScroll() = scroll.updatePadding(
            bottom = scrollBottom + if (dock.isVisible) dock.height else bottom,
        )
        dock.addOnLayoutChangeListener { _, _, top, _, end, _, oldTop, _, oldEnd ->
            if (end - top != oldEnd - oldTop) scroll.post { padScroll() }
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            // The keyboard is taller than the navigation bar it covers, so the
            // larger of the two is what keeps the guide reachable while a
            // field is being typed into.
            bottom = maxOf(bars.bottom, keyboard.bottom)
            bar.updatePadding(top = barTop + bars.top, left = sides.first + bars.left)
            scroll.updatePadding(left = sides.second + bars.left, right = sides.second + bars.right)
            dock.updatePadding(bottom = bottom)
            // Typing, the keyboard and the guide together would cover the very
            // field being filled in: the guide steps aside until it closes.
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

    private fun versionText(): String {
        val info = packageManager.getPackageInfo(packageName, 0)
        return getString(
            R.string.app_version,
            info.versionName.orEmpty(),
            PackageInfoCompat.getLongVersionCode(info).toInt(),
        )
    }

    // Both fields are read back with toLongOrNull(), so the digits have to stay
    // machine-parseable: a locale-formatted number is what SetTextI18n asks for
    // and is exactly what would fail to parse on a handset with non-Latin digits.
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
            val name = getString(kindName(read.kind))
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
            // A message's own time can run a few seconds ahead of this clock,
            // which read "in 0 minutes".
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
        // The labels are short so both fit on one row; the range and what 0
        // means are said while the field is being edited.
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
            resources.getQuantityString(R.plurals.status_held, it.rows, it.rows, it.collectedFor)
        }
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
                        SyncScheduler.uploadAfresh(this@SettingsActivity, settings.wifiOnlyUploads)
                    } else {
                        outbox.deleteHeldPositions(enrollment, held.enrollment)
                        outbox.withdrawHeld(enrollment, held.enrollment, System.currentTimeMillis())
                    }
                }
            }
            .show()
    }

    private fun TextView.showOptional(value: String?) {
        text = value.orEmpty()
        isVisible = value != null
    }

    // The figure is the number alone; what it counts is read aloud whole.
    @SuppressLint("SetTextI18n")
    private fun TextView.showFigure(value: Int, @StringRes spoken: Int) {
        text = value.toString()
        contentDescription = getString(spoken, value)
    }

    companion object {
        private const val STATUS_TICK_MILLIS = 60_000L

        private const val REBIND_GRACE_MILLIS = 3_000L

        private val FORM_KEYS = ManagedKey.ALL

        @StringRes
        fun kindName(kind: DocumentKind): Int = when (kind) {
            DocumentKind.CSF -> R.string.whatsapp_kind_csf
            DocumentKind.INE -> R.string.whatsapp_kind_ine
            DocumentKind.PASSPORT -> R.string.whatsapp_kind_passport
            DocumentKind.OTHER -> R.string.whatsapp_kind_other
        }
    }
}

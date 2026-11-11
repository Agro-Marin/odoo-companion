package com.odoocompanion.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.odoocompanion.CompanionApp
import com.odoocompanion.R
import com.odoocompanion.databinding.ActivityMainBinding
import com.odoocompanion.databinding.DialogAdminPasswordBinding
import com.odoocompanion.sync.SyncScheduler
import com.odoocompanion.update.InstallStart
import com.odoocompanion.update.UpdateCheck
import com.odoocompanion.update.UpdateManifest
import com.odoocompanion.update.UpdateRelaunch
import com.odoocompanion.update.UpdateStep
import com.odoocompanion.update.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

// Home: send a document, scanned or picked. Whether the phone is enrolled and
// what needs a hand stay in sight; configuring it is behind the gear, in
// Settings.
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val healthChecks = MutableStateFlow(0)

    private val grants = Grants(this) { healthChecks.value++ }

    private lateinit var alerts: AlertBand

    private lateinit var updater: Updater

    private var offered: UpdateManifest? = null

    private lateinit var scanCard: ScanCard

    // The alert band says how long ago things happened, as Settings does.
    private val minutes = flow {
        while (true) {
            emit(Unit)
            delay(STATUS_TICK_MILLIS)
        }
    }

    // Settings, Sync now, Grant and the update check live behind the gear, so
    // the screen keeps to the one thing it is for; the alert band keeps a
    // shortcut to Grant while one is due.
    private val closeActionsOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showActions(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        keepContentOutFromUnderTheSystemBars()

        val app = CompanionApp.from(this)
        updater = Updater(this)
        alerts = AlertBand(binding.alerts) { grants.requestAll() }
        setUpActions()
        scanCard = ScanCard(this, binding).also { it.start() }
        binding.appVersion.text = versionText()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    app.config.settings,
                    StatusText.queueCounts(app),
                    merge(healthChecks, minutes),
                    combine(
                        app.softphones.configured,
                        StatusText.localNetworkWanted(app, healthChecks),
                        ::Pair,
                    ),
                ) { settings, counts, _, (softphone, localNetwork) ->
                    StatusText.panel(
                        this@MainActivity,
                        settings,
                        counts,
                        null,
                        softphone,
                        localNetwork,
                    )
                }
                    .distinctUntilChanged()
                    .collect(::showPanel)
            }
        }
        binding.updateInstall.setOnClickListener { offered?.let(::install) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                updater.progress.collect(::showUpdateStep)
            }
        }
        if (savedInstanceState == null) {
            WhatsNewDialog(this, installedVersionCode()).showIfDue()
            handle(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action != ScanShortcut.ACTION_SCAN) return
        intent.action = null
        scanCard.scan()
    }

    private fun installedVersionCode(): Long =
        PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0))

    private fun versionText(): String = getString(
        R.string.app_version,
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
        installedVersionCode().toInt(),
    )

    private fun showUpdateStep(step: UpdateStep?) {
        val manifest = offered ?: return
        binding.updateProgress.isVisible = step != null
        when (step) {
            is UpdateStep.Downloading -> {
                binding.updateProgress.isIndeterminate = false
                binding.updateProgress.setProgressCompat(step.percent, true)
                binding.updateText.text =
                    getString(R.string.update_progress, manifest.versionName, step.percent)
            }

            UpdateStep.Verifying -> {
                binding.updateProgress.isIndeterminate = true
                binding.updateText.setText(R.string.update_verifying)
            }

            UpdateStep.Installing -> {
                binding.updateProgress.isIndeterminate = true
                binding.updateText.setText(R.string.update_installing)
            }

            null -> offer(manifest)
        }
    }

    override fun onResume() {
        super.onResume()
        healthChecks.value++
        UpdateRelaunch.dismiss(this)
        offer(updater.known())
        if (updater.dueForCheck()) checkForUpdate(quiet = true)
    }

    private fun setUpActions() {
        onBackPressedDispatcher.addCallback(this, closeActionsOnBack)
        binding.actionsToggle.setOnClickListener {
            showActions(!binding.actionsMenu.isVisible)
        }
        binding.actionsScrim.setOnClickListener { showActions(false) }
        binding.openSettings.setOnClickListener {
            showActions(false)
            openSettings()
        }
        binding.enrollOpenSettings.setOnClickListener { openSettings() }
        binding.syncNow.setOnClickListener {
            showActions(false)
            lifecycleScope.launch {
                val app = CompanionApp.from(this@MainActivity)
                SyncScheduler.collectAndUploadNow(
                    this@MainActivity,
                    app.config.current().wifiOnlyUploads,
                )
            }
        }
        binding.grantPermissions.setOnClickListener {
            showActions(false)
            grants.requestAll()
        }
        binding.checkUpdate.setOnClickListener {
            showActions(false)
            checkForUpdate(quiet = false)
        }
    }

    private fun openSettings() {
        val gate = CompanionApp.from(this).adminGate
        if (gate.isOpen()) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        val form = DialogAdminPasswordBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.admin_password_title)
            .setMessage(R.string.admin_password_message)
            .setView(form.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.admin_password_enter, null)
            .create()
        val submit = {
            if (gate.unlock(form.adminPassword.text?.toString().orEmpty())) {
                dialog.dismiss()
                startActivity(Intent(this, SettingsActivity::class.java))
            } else {
                form.adminPasswordLayout.error = getString(R.string.admin_password_wrong)
            }
        }
        form.adminPassword.setOnEditorActionListener { _, _, _ ->
            submit()
            true
        }
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                submit()
            }
            form.adminPassword.requestFocus()
        }
        dialog.show()
    }

    private fun showActions(open: Boolean) {
        binding.actionsMenu.isVisible = open
        binding.actionsScrim.isVisible = open
        closeActionsOnBack.isEnabled = open
    }

    // The automatic check says nothing unless it found something; the one
    // asked for from the menu always answers.
    private fun checkForUpdate(quiet: Boolean) {
        lifecycleScope.launch {
            when (val check = updater.check()) {
                is UpdateCheck.Available -> offer(check.manifest)

                UpdateCheck.Current -> {
                    offer(null)
                    if (!quiet) toast(R.string.update_none)
                }

                is UpdateCheck.Failed -> if (!quiet) toast(R.string.update_check_failed)
            }
        }
    }

    private fun offer(manifest: UpdateManifest?) {
        if (updater.progress.value != null && manifest != null) return
        offered = manifest
        binding.updateBanner.isVisible = manifest != null
        binding.updateInstall.isEnabled = true
        binding.updateProgress.isVisible = false
        if (manifest != null) {
            binding.updateText.text = getString(R.string.update_available, manifest.versionName)
        }
    }

    private fun install(manifest: UpdateManifest) {
        binding.updateInstall.isEnabled = false
        binding.updateText.text = getString(R.string.update_downloading, manifest.versionName)
        lifecycleScope.launch {
            when (updater.install(manifest)) {
                InstallStart.NeedsPermission -> {
                    toast(R.string.update_allow_installs)
                    allowInstalls()
                    offer(manifest)
                }

                // Android takes it from here: its confirmation, then a restart
                // into the new build.
                InstallStart.Started -> Unit

                is InstallStart.Failed -> {
                    toast(R.string.update_download_failed)
                    offer(manifest)
                }
            }
        }
    }

    private fun allowInstalls() {
        try {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:$packageName".toUri(),
                ),
            )
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No install-unknown-apps settings screen on this device", e)
        }
    }

    private fun showPanel(panel: PanelText) {
        binding.statePill.show(panel.enrolled)
        binding.enrollPrompt.isVisible = !panel.enrolled && !panel.managed
        alerts.show(panel.alerts, panel.blocked)
    }

    // targetSdk 36 means Android draws this window edge to edge and does not
    // ask: without this the header sits under the clock and the battery icon.
    private fun keepContentOutFromUnderTheSystemBars() {
        val scroll = binding.formScroll
        // Captured once. The listener runs again on rotation, and padding added
        // to whatever is currently set would grow a little further each time.
        val menuOffset =
            (binding.actionsMenu.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        val versionOffset =
            (binding.appVersion.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        val base = listOf(
            scroll.paddingLeft,
            scroll.paddingTop,
            scroll.paddingRight,
            scroll.paddingBottom,
        )
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            scroll.setPadding(
                base[0] + bars.left,
                base[1] + bars.top,
                base[2] + bars.right,
                base[3] + bars.bottom,
            )
            binding.actionsMenu.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = bars.top + menuOffset
            }
            binding.appVersion.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = bars.bottom + versionOffset
            }
            insets
        }
    }

    private fun toast(@StringRes message: Int) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val TAG = "MainActivity"

        const val STATUS_TICK_MILLIS = 60_000L
    }
}

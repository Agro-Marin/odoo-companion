package com.odoocompanion.ui

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.odoocompanion.CompanionApp
import com.odoocompanion.R
import com.odoocompanion.config.ManagedConfig
import com.odoocompanion.config.ManagedValues
import com.odoocompanion.config.enrol
import com.odoocompanion.config.form
import com.odoocompanion.data.OutboxEntry
import com.odoocompanion.data.OutboxKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
class StatusScreenTest {
    private val app: CompanionApp get() = ApplicationProvider.getApplicationContext()
    private var controller: ActivityController<MainActivity>? = null

    @Before
    fun setUp() = runTest {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        app.startup.join()

        app.config.clearEnrollment()
        app.config.applyManaged(ManagedValues())
        app.config.setFeature(callLog = true, recordings = false)

        withContext(Dispatchers.IO) { app.database.clearAllTables() }
    }

    @After
    fun tearDown() {
        controller?.destroy()
        controller = null
    }

    private fun batteryExemption(granted: Boolean) {
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        Shadows.shadowOf(power).setIgnoringBatteryOptimizations(app.packageName, granted)
    }

    private fun open(): MainActivity {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        return controller!!.get()
    }

    private fun status(): String {
        val view = open().findViewById<TextView>(R.id.status)
        var last = ""
        repeat(60) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val now = view.text.toString()
            if (now.isNotEmpty() && now == last) return now
            last = now
            Thread.sleep(20)
        }
        return last
    }

    private fun text(id: Int) = app.getString(id)

    @Test
    fun `a phone with nothing granted names the permission, not what it implies`() {
        batteryExemption(false)

        val shown = status()

        assertTrue(shown, text(R.string.status_location_missing) in shown)

        assertFalse(shown, text(R.string.status_background_location_missing) in shown)
        assertTrue(shown, text(R.string.status_battery_optimized) in shown)
    }

    @Test
    fun `foreground-only location is named once the grant exists`() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        batteryExemption(true)

        val shown = status()

        assertTrue(shown, text(R.string.status_background_location_missing) in shown)
        assertFalse(shown, text(R.string.status_location_missing) in shown)
    }

    @Test
    fun `call sync without its permission is named`() = runTest {
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        batteryExemption(true)
        app.config.setFeature(callLog = true)

        assertTrue(text(R.string.status_call_log_permission_missing) in status())
    }

    @Test
    fun `call sync switched off is not a missing permission`() = runTest {
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        batteryExemption(true)
        app.config.setFeature(callLog = false)

        assertFalse(text(R.string.status_call_log_permission_missing) in status())
    }

    @Test
    fun `queued rows are counted per kind`() = runTest {
        app.database.outbox().insertAll(
            listOf(
                OutboxEntry(kind = OutboxKind.LOCATION, payload = "{}", createdAt = 1),
                OutboxEntry(kind = OutboxKind.LOCATION, payload = "{}", createdAt = 2),
                OutboxEntry(kind = OutboxKind.CALL_LOG, payload = "{}", createdAt = 3),
            ),
        )

        val shown = status()

        assertTrue(shown, app.getString(R.string.status_queued_positions, 2) in shown)
        assertTrue(shown, app.getString(R.string.status_queued_calls, 1) in shown)
        assertTrue(shown, app.getString(R.string.status_queued_recordings, 0) in shown)
    }

    @Test
    fun `undeliverable rows are counted, and the screen says what fixes them`() = runTest {
        val dao = app.database.outbox()
        dao.insert(OutboxEntry(kind = OutboxKind.CALL_LOG, payload = "{}", createdAt = 1))
        dao.markDeadIds(dao.take(OutboxKind.CALL_LOG, 1).map { it.id }, 1_000L, "refused")

        val shown = status()

        assertTrue(
            shown,
            app.resources.getQuantityString(R.plurals.status_undeliverable, 1, 1) in shown
        )
        assertTrue(shown, text(R.string.status_undeliverable_hint) in shown)
    }

    @Test
    fun `a healthy enrolled device says so without a blocker line`() = runTest {
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.READ_CALL_LOG,
        )
        batteryExemption(true)
        app.config.enrol("https://odoo.example.com", "phone-01", "t".repeat(64))

        val shown = status()

        assertTrue(shown, text(R.string.status_enrolled) in shown)
        assertFalse(shown, text(R.string.status_battery_optimized) in shown)
        assertFalse(shown, text(R.string.status_undeliverable_hint) in shown)
    }

    private fun policy(vararg entries: Pair<String, Any>) = ManagedConfig.fromBundle(
        Bundle().apply {
            entries.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    else -> error("unsupported restriction type for $key")
                }
            }
        },
    )

    // Every key, which is what a console that has actually been filled in
    // sends. Save goes with them: there is nothing left on the form to save.
    @Test
    fun `a device managed in full has its form locked, including the interval`() = runTest {
        app.config.applyManaged(
            policy(
                "base_url" to "https://odoo.example.com",
                "identifier" to "phone-01",
                "token" to "token",
                "location_interval_seconds" to 120,
                "upload_window_seconds" to 60,
                "min_move_metres" to 25,
                "call_log_enabled" to true,
                "recordings_enabled" to false,
                "wifi_only_uploads" to true,
            ),
        )

        val activity = open()

        assertFalse(activity.findViewById<View>(R.id.save).isEnabled)
        assertFalse(activity.findViewById<View>(R.id.locationInterval).isEnabled)
        assertFalse(activity.findViewById<View>(R.id.uploadWindow).isEnabled)

        assertTrue(activity.findViewById<View>(R.id.syncNow).isEnabled)
    }

    // Save is the form's button, not any one field's: while a single field the
    // policy left alone is still editable, there is something to save.
    @Test
    fun `a partly managed form can still be saved`() = runTest {
        app.config.applyManaged(
            policy(
                "base_url" to "https://odoo.example.com",
                "identifier" to "phone-01",
                "token" to "token",
            ),
        )

        val activity = open()

        assertFalse(activity.findViewById<View>(R.id.baseUrl).isEnabled)
        assertTrue(activity.findViewById<View>(R.id.wifiOnly).isEnabled)
        assertTrue(activity.findViewById<View>(R.id.save).isEnabled)
    }

    // The case this whole distinction exists for: a console that publishes the
    // declared defaults of app_restrictions.xml and nothing an administrator
    // typed. The device is managed -- a policy did arrive -- but the fields it
    // never mentioned stay usable, so the handset can still be enrolled.
    @Test
    fun `a policy that never mentioned enrolment leaves those fields usable`() = runTest {
        app.config.applyManaged(
            policy(
                "call_log_enabled" to true,
                "recordings_enabled" to false,
                "wifi_only_uploads" to false,
            ),
        )

        val activity = open()

        assertTrue(activity.findViewById<View>(R.id.baseUrl).isEnabled)
        assertTrue(activity.findViewById<View>(R.id.identifier).isEnabled)
        assertTrue(activity.findViewById<View>(R.id.token).isEnabled)
        assertTrue(activity.findViewById<View>(R.id.save).isEnabled)
        // What the policy did mention is still governed by it.
        assertFalse(activity.findViewById<View>(R.id.callLogEnabled).isEnabled)
    }

    // A key that arrived and turned out unusable is still a key the
    // administrator is setting, so it stays locked. This is the case that
    // policyPresent was added for, and it must keep behaving that way.
    @Test
    fun `a base URL the policy got wrong still locks the base URL`() = runTest {
        app.config.applyManaged(policy("base_url" to "not a url at all"))

        val activity = open()

        assertFalse(activity.findViewById<View>(R.id.baseUrl).isEnabled)
        assertTrue(activity.findViewById<View>(R.id.identifier).isEnabled)
    }

    @Test
    fun `the form carries the stored interval and clamps what is typed`() = runTest {
        app.config.saveForm(form(locationIntervalSeconds = 120))

        val activity = open()
        val field = activity.findViewById<android.widget.EditText>(R.id.locationInterval)
        assertTrue(field.text.toString() == "120")

        // Saved against a valid enrolment and waited for: asserted straight
        // after the click, the stored 120 satisfied ">= 15" before Save had
        // written anything, so the clamp was never what the test observed.
        activity.type(R.id.baseUrl, "https://odoo.example.com")
        activity.type(R.id.identifier, "phone-01")
        activity.type(R.id.token, "t")
        field.setText("1")
        activity.findViewById<View>(R.id.save).performClick()
        settle()

        assertTrue(
            app.config.current().toString(),
            app.config.current().locationIntervalSeconds == 15L,
        )
    }

    private fun settle() = repeat(50) {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(20)
    }

    private fun MainActivity.type(id: Int, value: String) =
        findViewById<android.widget.EditText>(id).setText(value)

    // The case a partial policy exists for: the console publishes the switches
    // and the person holding the phone types the enrolment. Save writes the
    // switches first, every write re-emits the settings, and a managed device
    // used to repaint the whole form on each emission -- so the typed fields
    // were back to their stored, empty values by the time Save read them.
    @Test
    fun `a partly managed phone saves the enrolment typed into it`() = runTest {
        app.config.applyManaged(policy("call_log_enabled" to true, "wifi_only_uploads" to false))
        val activity = open()
        settle()

        activity.type(R.id.baseUrl, "https://odoo.example.com")
        activity.type(R.id.identifier, "phone-07")
        activity.type(R.id.token, "typed-token")
        activity.findViewById<View>(R.id.save).performClick()
        settle()

        val saved = app.config.current()
        assertTrue(saved.toString(), saved.identifier == "phone-07")
        assertTrue(saved.toString(), saved.baseUrl == "https://odoo.example.com")
    }

    @Test
    fun `a background write does not wipe what is being typed on a managed phone`() = runTest {
        app.config.applyManaged(policy("call_log_enabled" to true))
        val activity = open()
        settle()

        activity.type(R.id.identifier, "phone-07")
        app.config.recordUpload(at = 1_000L, delivered = false, error = "offline")
        settle()

        assertTrue(
            activity.findViewById<android.widget.EditText>(R.id.identifier).text.toString() ==
                "phone-07",
        )
    }

    // Save used to write each switch as its own edit before validating the
    // enrolment, and on a managed phone every edit repainted the form -- so a
    // switch the person changed was the write that put the stored, empty
    // enrolment back into the fields Save was about to read.
    @Test
    fun `a partly managed phone saves the enrolment typed beside a changed switch`() = runTest {
        app.config.applyManaged(policy("call_log_enabled" to true))
        val activity = open()
        settle()

        activity.type(R.id.baseUrl, "https://odoo.example.com")
        activity.type(R.id.identifier, "phone-07")
        activity.type(R.id.token, "typed-token")
        activity.findViewById<android.widget.CompoundButton>(R.id.wifiOnly).isChecked = true
        activity.findViewById<View>(R.id.save).performClick()
        settle()

        val saved = app.config.current()
        assertTrue(saved.toString(), saved.identifier == "phone-07")
        assertTrue(saved.toString(), saved.wifiOnlyUploads)
    }

    @Test
    fun `a refused enrolment saves none of the form`() = runTest {
        val activity = open()
        settle()

        activity.type(R.id.baseUrl, "odoo.example.com")
        activity.findViewById<android.widget.CompoundButton>(R.id.wifiOnly).isChecked = true
        activity.findViewById<View>(R.id.save).performClick()
        settle()

        assertFalse(app.config.current().wifiOnlyUploads)
    }

    // A recording that was delivered and whose file would not delete is kept
    // only as the harvest's memory of that file. It is not undeliverable, and
    // counting it told an operator to fix a device that had delivered it.
    @Test
    fun `a delivered recording kept on disk is not counted as undeliverable`() = runTest {
        val dao = app.database.outbox()
        dao.insert(OutboxEntry(kind = OutboxKind.RECORDING, payload = "{}", createdAt = 1))
        dao.markDeadIds(
            dao.take(OutboxKind.RECORDING, 1).map { it.id },
            1_000L,
            "uploaded, but the file could not be deleted",
            com.odoocompanion.data.DeadReason.KEPT_ON_DISK,
        )

        val shown = status()

        assertFalse(shown, text(R.string.status_undeliverable_hint) in shown)
    }

    // The queue changes on every fix and every upload without a setting
    // changing, and the screen used to redraw only when a setting did.
    @Test
    fun `the queue counts follow the queue while the screen is open`() = runTest {
        val activity = open()
        settle()

        app.database.outbox().insert(
            OutboxEntry(kind = OutboxKind.LOCATION, payload = "{}", createdAt = 1),
        )
        settle()

        val shown = activity.findViewById<TextView>(R.id.status).text.toString()
        assertTrue(shown, app.getString(R.string.status_queued_positions, 1) in shown)
    }

    @Test
    fun `a refused save stays on screen through a redraw`() = runTest {
        val activity = open()
        settle()

        activity.type(R.id.baseUrl, "odoo.example.com")
        activity.findViewById<View>(R.id.save).performClick()
        settle()
        app.database.outbox().insert(
            OutboxEntry(kind = OutboxKind.LOCATION, payload = "{}", createdAt = 1),
        )
        settle()

        val shown = activity.findViewById<TextView>(R.id.status).text.toString()
        assertTrue(shown, text(R.string.status_bad_base_url) in shown)
    }

    // The battery-exemption prompt and the permission dialogs only pause this
    // screen, so nothing that restarts on STARTED ever re-read the grants.
    @Test
    fun `a grant made behind a dialog shows once the screen resumes`() = runTest {
        batteryExemption(true)
        open()
        settle()
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )

        controller!!.pause().resume()
        settle()

        val shown = controller!!.get().findViewById<TextView>(R.id.status).text.toString()
        assertFalse(shown, text(R.string.status_location_missing) in shown)
    }

    // Android 12 lets the person choose approximate. Only a precise grant used
    // to lead on to the all-the-time prompt, so an approximate one stopped at
    // "while using the app" -- the grant that goes quiet after a reboot.
    @Test
    fun `an approximate grant still leads on to the all-the-time prompt`() = runTest {
        val activity = open()
        settle()
        activity.findViewById<View>(R.id.grantPermissions).performClick()
        settle()
        val shadow = Shadows.shadowOf(activity)
        val asked = shadow.lastRequestedPermission
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)

        activity.onRequestPermissionsResult(
            asked.requestCode,
            asked.requestedPermissions,
            asked.requestedPermissions.map {
                if (it == Manifest.permission.ACCESS_COARSE_LOCATION) {
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                } else {
                    android.content.pm.PackageManager.PERMISSION_DENIED
                }
            }.toIntArray(),
        )
        settle()

        assertTrue(
            shadow.lastRequestedPermission.requestedPermissions.toList().toString(),
            shadow.lastRequestedPermission.requestedPermissions.toList() ==
                listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        )
    }
}

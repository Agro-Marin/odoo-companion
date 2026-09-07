package com.odoocompanion.ui

import android.Manifest
import android.content.Context
import android.os.PowerManager
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.odoocompanion.CompanionApp
import com.odoocompanion.R
import com.odoocompanion.config.ManagedValues
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
        app.config.saveEnrollment("https://odoo.example.com", "phone-01", "t".repeat(64))

        val shown = status()

        assertTrue(shown, text(R.string.status_enrolled) in shown)
        assertFalse(shown, text(R.string.status_battery_optimized) in shown)
        assertFalse(shown, text(R.string.status_undeliverable_hint) in shown)
    }

    @Test
    fun `a managed device has its form locked, including the interval`() = runTest {
        app.config.applyManaged(ManagedValues(identifier = "phone-01"))

        val activity = open()

        assertFalse(activity.findViewById<View>(R.id.save).isEnabled)
        assertFalse(activity.findViewById<View>(R.id.locationInterval).isEnabled)
        assertFalse(activity.findViewById<View>(R.id.uploadWindow).isEnabled)

        assertTrue(activity.findViewById<View>(R.id.syncNow).isEnabled)
    }

    @Test
    fun `the form carries the stored interval and clamps what is typed`() = runTest {
        app.config.setLocationInterval(120)

        val activity = open()
        val field = activity.findViewById<android.widget.EditText>(R.id.locationInterval)
        assertTrue(field.text.toString() == "120")

        field.setText("1")
        activity.findViewById<View>(R.id.save).performClick()

        assertTrue(
            app.config.current().locationIntervalSeconds.toString(),
            app.config.current().locationIntervalSeconds >= 15,
        )
    }
}

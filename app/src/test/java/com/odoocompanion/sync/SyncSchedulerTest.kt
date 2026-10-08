package com.odoocompanion.sync

import android.Manifest
import android.app.Application
import android.provider.CallLog
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.odoocompanion.config.Settings
import com.odoocompanion.recording.RecordingScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SyncSchedulerTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    private fun states(name: String): List<WorkInfo.State> =
        WorkManager.getInstance(app).getWorkInfosForUniqueWork(name).get().map { it.state }

    private fun live(name: String) = states(name).any { !it.isFinished }

    private fun enrolled(callLog: Boolean = true, recordings: Boolean = true) = Settings(
        baseUrl = "https://odoo.example.com",
        identifier = "phone-01",
        token = "t".repeat(64),
        callLogEnabled = callLog,
        recordingsEnabled = recordings,
    )

    @Test
    fun `an enrolled device with both features on runs all three`() {
        SyncScheduler.schedulePeriodicWork(app, enrolled())

        assertTrue(live(SyncScheduler.UPLOAD_PERIODIC))
        assertTrue(live(SyncScheduler.CALL_LOG_PERIODIC))
        assertTrue(live(SyncScheduler.RECORDING_PERIODIC))
    }

    @Test
    fun `a collector whose feature is off is not registered`() {
        SyncScheduler.schedulePeriodicWork(app, enrolled(callLog = false, recordings = false))

        assertTrue(live(SyncScheduler.UPLOAD_PERIODIC))
        assertEquals(emptyList<WorkInfo.State>(), states(SyncScheduler.CALL_LOG_PERIODIC))
        assertEquals(emptyList<WorkInfo.State>(), states(SyncScheduler.RECORDING_PERIODIC))
    }

    @Test
    fun `switching a feature off cancels the collector already registered`() {
        SyncScheduler.schedulePeriodicWork(app, enrolled())
        assertTrue(live(SyncScheduler.RECORDING_PERIODIC))

        SyncScheduler.schedulePeriodicWork(app, enrolled(recordings = false))

        assertTrue(states(SyncScheduler.RECORDING_PERIODIC).all { it.isFinished })
        assertTrue("call sync was left alone", live(SyncScheduler.CALL_LOG_PERIODIC))
    }

    @Test
    fun `an unenrolled device collects nothing and still drains`() {
        SyncScheduler.schedulePeriodicWork(app, enrolled())

        SyncScheduler.schedulePeriodicWork(app, Settings())

        assertTrue(live(SyncScheduler.UPLOAD_PERIODIC))
        assertTrue(states(SyncScheduler.CALL_LOG_PERIODIC).all { it.isFinished })
        assertTrue(states(SyncScheduler.RECORDING_PERIODIC).all { it.isFinished })
    }

    @Test
    fun `reconciling twice leaves one registration, not two`() {
        SyncScheduler.schedulePeriodicWork(app, enrolled())
        SyncScheduler.schedulePeriodicWork(app, enrolled())

        assertEquals(1, states(SyncScheduler.CALL_LOG_PERIODIC).count { !it.isFinished })
    }

    private fun grantCallLog() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CALL_LOG)
    }

    private fun info(name: String): WorkInfo =
        WorkManager.getInstance(app).getWorkInfosForUniqueWork(name).get().single()

    @Test
    fun `an enrolled device with call-log access watches the call log`() {
        grantCallLog()

        SyncScheduler.schedulePeriodicWork(app, enrolled())

        assertTrue(live(SyncScheduler.CALL_ENDED_TRIGGER))
        val triggers = info(SyncScheduler.CALL_ENDED_TRIGGER).constraints.contentUriTriggers
        assertEquals(setOf(CallLog.Calls.CONTENT_URI), triggers.map { it.uri }.toSet())
        assertTrue(triggers.single().isTriggeredForDescendants)
    }

    @Test
    fun `without call-log access the call log is not watched`() {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_CALL_LOG)

        SyncScheduler.schedulePeriodicWork(app, enrolled())

        assertFalse(live(SyncScheduler.CALL_ENDED_TRIGGER))
    }

    @Test
    fun `recordings alone are enough to watch the call log`() {
        grantCallLog()

        SyncScheduler.schedulePeriodicWork(app, enrolled(callLog = false))

        assertTrue(live(SyncScheduler.CALL_ENDED_TRIGGER))
    }

    @Test
    fun `switching both collectors off cancels the call-log trigger`() {
        grantCallLog()
        SyncScheduler.schedulePeriodicWork(app, enrolled())

        SyncScheduler.schedulePeriodicWork(app, enrolled(callLog = false, recordings = false))

        assertTrue(states(SyncScheduler.CALL_ENDED_TRIGGER).all { it.isFinished })
    }

    @Test
    fun `reconciling twice keeps one armed trigger`() {
        grantCallLog()
        SyncScheduler.schedulePeriodicWork(app, enrolled())
        val armed = info(SyncScheduler.CALL_ENDED_TRIGGER).id

        SyncScheduler.schedulePeriodicWork(app, enrolled())

        assertEquals(armed, info(SyncScheduler.CALL_ENDED_TRIGGER).id)
    }

    @Test
    fun `after a call the log is read and the recording harvest waits for it to settle`() {
        SyncScheduler.afterCall(app, enrolled())

        assertTrue(live(SyncScheduler.CALL_LOG_NOW))
        val harvest = info(SyncScheduler.RECORDING_AFTER_CALL)
        assertEquals(WorkInfo.State.ENQUEUED, harvest.state)
        assertEquals(SyncScheduler.RECORDING_AFTER_CALL_DELAY_MILLIS, harvest.initialDelayMillis)
        assertTrue(
            "the scanner would still find the file too fresh",
            harvest.initialDelayMillis > RecordingScanner.SETTLE_MILLIS,
        )
    }

    @Test
    fun `a second call pushes the pending harvest back instead of adding one`() {
        SyncScheduler.afterCall(app, enrolled())
        SyncScheduler.afterCall(app, enrolled())

        assertEquals(1, states(SyncScheduler.RECORDING_AFTER_CALL).count { !it.isFinished })
    }

    @Test
    fun `after a call only the collectors switched on run`() {
        SyncScheduler.afterCall(app, enrolled(recordings = false))

        assertTrue(live(SyncScheduler.CALL_LOG_NOW))
        assertEquals(emptyList<WorkInfo.State>(), states(SyncScheduler.RECORDING_AFTER_CALL))
    }

    @Test
    fun `after a call an unenrolled device collects nothing`() {
        SyncScheduler.afterCall(app, Settings())

        assertEquals(emptyList<WorkInfo.State>(), states(SyncScheduler.CALL_LOG_NOW))
        assertEquals(emptyList<WorkInfo.State>(), states(SyncScheduler.RECORDING_AFTER_CALL))
    }
}

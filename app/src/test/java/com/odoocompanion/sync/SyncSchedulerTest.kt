package com.odoocompanion.sync

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.odoocompanion.config.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
}

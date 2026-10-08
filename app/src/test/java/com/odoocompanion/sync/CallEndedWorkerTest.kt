package com.odoocompanion.sync

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.odoocompanion.CompanionApp
import com.odoocompanion.config.enrol
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class CallEndedWorkerTest {
    private val app: CompanionApp get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    private fun states(name: String): List<WorkInfo.State> =
        WorkManager.getInstance(app).getWorkInfosForUniqueWork(name).get().map { it.state }

    private suspend fun run(): ListenableWorker.Result =
        TestListenableWorkerBuilder<CallEndedWorker>(app).build().doWork()

    @Test
    fun `a call ending collects and arms the next trigger`() = runTest {
        app.startup.join()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CALL_LOG)
        app.config.enrol("https://odoo.example.com", "phone-01", "token")
        app.config.setFeature(callLog = true, recordings = true)

        assertEquals(ListenableWorker.Result.success(), run())

        assertTrue(states(SyncScheduler.RECORDING_AFTER_CALL).any { !it.isFinished })
        assertTrue(states(SyncScheduler.CALL_ENDED_TRIGGER).any { !it.isFinished })
    }

    @Test
    fun `an unenrolled device neither collects nor re-arms`() = runTest {
        app.startup.join()
        app.config.clearEnrollment()

        assertEquals(ListenableWorker.Result.success(), run())

        assertEquals(emptyList<WorkInfo.State>(), states(SyncScheduler.RECORDING_AFTER_CALL))
        assertEquals(emptyList<WorkInfo.State>(), states(SyncScheduler.CALL_ENDED_TRIGGER))
    }
}

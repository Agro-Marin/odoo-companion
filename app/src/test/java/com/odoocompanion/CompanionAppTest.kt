package com.odoocompanion

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.odoocompanion.sync.SyncScheduler
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CompanionAppTest {
    private val app: CompanionApp get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    private fun periodicUploadNetwork(): NetworkType = WorkManager.getInstance(app)
        .getWorkInfosForUniqueWork(SyncScheduler.UPLOAD_PERIODIC)
        .get()
        .first()
        .constraints
        .requiredNetworkType

    @Test
    fun `startup keeps the stored wifi-only constraint`() = runTest {
        app.startup.join()
        app.config.setWifiOnlyUploads(true)

        app.applyConfiguration()

        assertEquals(NetworkType.UNMETERED, periodicUploadNetwork())
    }

    @Test
    fun `startup applies a metered-allowed setting just as faithfully`() = runTest {
        app.startup.join()
        app.config.setWifiOnlyUploads(false)

        app.applyConfiguration()

        assertEquals(NetworkType.CONNECTED, periodicUploadNetwork())
    }

    @Test
    fun `concurrent reconfigurations settle on the last word, not a stale one`() = runTest {
        app.startup.join()
        app.config.setWifiOnlyUploads(false)

        // Startup, an ACTION_APPLICATION_RESTRICTIONS_CHANGED broadcast and a
        // boot all launch applyConfiguration on the same scope. Interleaved,
        // a pass that read the configuration before a managed push could get
        // the last word over one that read it after.
        val passes = (1..6).map { async { app.applyConfiguration() } }
        app.config.setWifiOnlyUploads(true)
        passes.awaitAll()
        app.applyConfiguration()

        assertEquals(NetworkType.UNMETERED, periodicUploadNetwork())
    }
}

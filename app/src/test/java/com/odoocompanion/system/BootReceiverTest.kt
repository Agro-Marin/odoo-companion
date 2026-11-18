package com.odoocompanion.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.odoocompanion.CompanionApp
import com.odoocompanion.config.Settings
import com.odoocompanion.sync.SyncScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class BootReceiverTest {
    private val app: CompanionApp get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = runTest {
        app.config.clearEnrollment()
    }

    private fun receive(action: String) {
        val finished = CountDownLatch(1)
        val result = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = finished.countDown()
        }
        app.sendOrderedBroadcast(
            Intent(app, BootReceiver::class.java).setAction(action),
            null,
            result,
            null,
            0,
            null,
            null,
        )
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (finished.await(10, TimeUnit.MILLISECONDS)) return
        } while (System.nanoTime() < deadline)
        throw AssertionError("Broadcast did not finish: $action")
    }

    private fun states(name: String): List<WorkInfo.State> =
        WorkManager.getInstance(app).getWorkInfosForUniqueWork(name).get().map { it.state }

    @Test
    fun `an action this receiver does not handle is ignored`() {
        receive(Intent.ACTION_SCREEN_ON)
    }

    @Test
    fun `both handled actions are accepted without throwing`() {
        receive(Intent.ACTION_BOOT_COMPLETED)
        receive(Intent.ACTION_MY_PACKAGE_REPLACED)
    }

    @Test
    fun `a reboot cancels collectors left behind by an enrollment that is gone`() = runTest {
        SyncScheduler.schedulePeriodicWork(
            app,
            Settings(
                baseUrl = "https://odoo.example.com",
                identifier = "phone-01",
                token = "t".repeat(64),
                callLogEnabled = true,
                recordingsEnabled = true,
            ),
        )
        assertTrue(states(SyncScheduler.CALL_LOG_PERIODIC).any { !it.isFinished })
        app.config.clearEnrollment()

        receive(Intent.ACTION_BOOT_COMPLETED)

        assertTrue(states(SyncScheduler.CALL_LOG_PERIODIC).all { it.isFinished })
        assertTrue(states(SyncScheduler.RECORDING_PERIODIC).all { it.isFinished })

        assertTrue(states(SyncScheduler.UPLOAD_PERIODIC).any { !it.isFinished })
    }

    @Test
    fun `an unhandled action changes nothing`() {
        val before = states(SyncScheduler.UPLOAD_PERIODIC)

        receive("com.example.SOMETHING_ELSE")

        assertEquals(before, states(SyncScheduler.UPLOAD_PERIODIC))
    }
}

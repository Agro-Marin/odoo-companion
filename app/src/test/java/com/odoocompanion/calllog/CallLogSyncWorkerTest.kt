package com.odoocompanion.calllog

import android.Manifest
import android.provider.CallLog
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.odoocompanion.CompanionApp
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.sync.SyncScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
class CallLogSyncWorkerTest {
    private val app: CompanionApp get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        FakeCallLogProvider.calls = emptyList()
        FakeCallLogProvider.returnsNull = false
        ShadowContentResolver.registerProviderInternal(
            CallLog.AUTHORITY,
            FakeCallLogProvider().apply { onCreate() },
        )
    }

    private fun grantCallLog() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CALL_LOG)
    }

    private fun denyCallLog() {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_CALL_LOG)
    }

    private suspend fun enroll() {
        app.config.saveEnrollment("https://odoo.example.com", "phone-01", "token")
    }

    private suspend fun run(): ListenableWorker.Result =
        TestListenableWorkerBuilder<CallLogSyncWorker>(app).build().doWork()

    private suspend fun reset() {
        app.config.clearEnrollment()
        app.config.setCallLogCursor(0)
        app.config.setCallLogSeen(emptySet())
        app.config.setFeature(callLog = true)
        val dao = app.database.outbox()
        dao.delete(dao.take(OutboxKind.CALL_LOG, 1000).map { it.id })
    }

    @Test
    fun `an unenrolled device reads nothing`() = runTest {
        reset()
        grantCallLog()
        FakeCallLogProvider.calls = listOf(Call(number = "+52", date = 10))

        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(0, app.database.outbox().countOf(OutboxKind.CALL_LOG))
    }

    @Test
    fun `no call log permission is a no-op, not a failure`() = runTest {
        reset()
        enroll()
        denyCallLog()
        FakeCallLogProvider.calls = listOf(Call(number = "+52", date = 10))

        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(0, app.database.outbox().countOf(OutboxKind.CALL_LOG))
        assertEquals(0, app.config.callLogCursor())
    }

    @Test
    fun `the feature switch being off reads nothing`() = runTest {
        reset()
        enroll()
        grantCallLog()
        app.config.setFeature(callLog = false)
        FakeCallLogProvider.calls = listOf(Call(number = "+52", date = 10))

        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(0, app.database.outbox().countOf(OutboxKind.CALL_LOG))
    }

    @Test
    fun `an enrolled and permitted device queues calls and advances the cursor`() = runTest {
        reset()
        enroll()
        grantCallLog()
        FakeCallLogProvider.calls = listOf(
            Call(number = "+525511111111", date = 10),
            Call(number = "+525522222222", date = 20),
        )

        assertEquals(ListenableWorker.Result.success(), run())

        assertEquals(2, app.database.outbox().countOf(OutboxKind.CALL_LOG))
        assertEquals(20, app.config.callLogCursor())
    }

    @Test
    fun `a second pass over the same call log queues nothing`() = runTest {
        reset()
        enroll()
        grantCallLog()
        FakeCallLogProvider.calls = listOf(Call(number = "+525511111111", date = 10))

        run()
        run()

        assertEquals(1, app.database.outbox().countOf(OutboxKind.CALL_LOG))
    }

    @Test
    fun `a call written after the cursor moved past its date is still queued`() = runTest {
        reset()
        enroll()
        grantCallLog()
        // The short call ends first, so the provider writes it first, and it
        // carries the later date. The long one started earlier and is written
        // when it ends -- after the cursor has already moved past its date.
        FakeCallLogProvider.calls = listOf(Call(number = "+52short", date = 20))
        run()
        assertEquals(20, app.config.callLogCursor())

        FakeCallLogProvider.calls = listOf(
            Call(number = "+52long", date = 10),
            Call(number = "+52short", date = 20),
        )

        assertEquals(ListenableWorker.Result.success(), run())

        assertEquals(2, app.database.outbox().countOf(OutboxKind.CALL_LOG))
        assertEquals(20, app.config.callLogCursor())
    }

    @Test
    fun `reading behind the cursor does not walk the cursor backwards`() = runTest {
        reset()
        enroll()
        grantCallLog()
        FakeCallLogProvider.calls = listOf(Call(number = "+525511111111", date = 20))
        run()

        FakeCallLogProvider.calls = emptyList()

        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(20, app.config.callLogCursor())
    }

    @Test
    fun `an unavailable provider leaves the cursor alone`() = runTest {
        reset()
        enroll()
        grantCallLog()
        FakeCallLogProvider.returnsNull = true

        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(0, app.config.callLogCursor())
    }

    @Test
    fun `a read stopped at its limit asks for another pass`() = runTest {
        reset()
        enroll()
        grantCallLog()
        FakeCallLogProvider.calls = (1..CallLogReader.DEFAULT_LIMIT + 5).map {
            Call(number = "+5255$it", date = it.toLong())
        }

        assertEquals(ListenableWorker.Result.success(), run())

        assertTrue(
            "no continuation enqueued",
            WorkManager.getInstance(app)
                .getWorkInfosForUniqueWork(SyncScheduler.CALL_LOG_NOW)
                .get()
                .isNotEmpty(),
        )
    }

    @Test
    fun `a read that reached the end asks for nothing more`() = runTest {
        reset()
        enroll()
        grantCallLog()
        FakeCallLogProvider.calls = listOf(Call(number = "+52", date = 10))

        assertEquals(ListenableWorker.Result.success(), run())

        assertTrue(
            WorkManager.getInstance(app)
                .getWorkInfosForUniqueWork(SyncScheduler.CALL_LOG_NOW)
                .get()
                .isEmpty(),
        )
    }
}

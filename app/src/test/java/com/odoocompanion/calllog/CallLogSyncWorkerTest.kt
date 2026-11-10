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
import com.odoocompanion.config.Settings
import com.odoocompanion.config.enrol
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowContentResolver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        FakeCallLogProvider.onRead = {}
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
        app.config.enrol("https://odoo.example.com", "phone-01", "token")
    }

    // Every call is queued for the enrolment it was read under.
    private suspend fun queued(): Int =
        app.database.outbox().countOf(OutboxKind.CALL_LOG, enrollment = ENROLMENT)

    private suspend fun run(vararg tags: String): ListenableWorker.Result =
        TestListenableWorkerBuilder<CallLogSyncWorker>(app).setTags(tags.toList()).build().doWork()

    private fun uploadsAsked(): Int = WorkManager.getInstance(app)
        .getWorkInfosForUniqueWork("upload-now").get().size

    private suspend fun reset() {
        app.config.clearEnrollment()
        app.config.setCallLogCursor(0)
        app.config.setCallLogIdCursor(0)
        app.config.setFeature(callLog = true)
        val dao = app.database.outbox()
        dao.delete(dao.take(OutboxKind.CALL_LOG, 1000, enrollment = ENROLMENT).map { it.id })
    }

    @Test
    fun `an unenrolled device reads nothing`() = runTest {
        reset()
        grantCallLog()
        FakeCallLogProvider.calls = listOf(Call(number = "+52", date = 10))

        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(0, queued())
    }

    @Test
    fun `no call log permission is a no-op, not a failure`() = runTest {
        reset()
        enroll()
        denyCallLog()
        FakeCallLogProvider.calls = listOf(Call(number = "+52", date = 10))

        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(0, queued())
        assertEquals(0L, app.config.callLogIdCursor())
    }

    @Test
    fun `the feature switch being off reads nothing`() = runTest {
        reset()
        enroll()
        grantCallLog()
        app.config.setFeature(callLog = false)
        FakeCallLogProvider.calls = listOf(Call(number = "+52", date = 10))

        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(0, queued())
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

        assertEquals(2, queued())
        assertEquals(2L, app.config.callLogIdCursor())
        assertEquals(
            "stamped with the enrolment it was read under",
            setOf(ENROLMENT),
            app.database.outbox().take(OutboxKind.CALL_LOG, 10, enrollment = ENROLMENT)
                .map { it.enrollment }.toSet(),
        )
    }

    @Test
    fun `a collector chained ahead of an upload does not ask for a second one`() = runTest {
        reset()
        enroll()
        grantCallLog()
        FakeCallLogProvider.calls = listOf(Call(number = "+525511111111", date = 10))

        run(SyncScheduler.UPLOAD_FOLLOWS)
        assertEquals(0, uploadsAsked())

        FakeCallLogProvider.calls += Call(number = "+525522222222", date = 20)
        run()
        assertEquals(1, uploadsAsked())
    }

    @Test
    fun `a second pass over the same call log queues nothing`() = runTest {
        reset()
        enroll()
        grantCallLog()
        FakeCallLogProvider.calls = listOf(Call(number = "+525511111111", date = 10))

        run()
        run()

        assertEquals(1, queued())
    }

    @Test
    fun `a call log cleared on the handset is read again from the beginning`() = runTest {
        reset()
        enroll()
        grantCallLog()
        FakeCallLogProvider.calls = (1..4).map { Call(number = "+5255$it", date = it.toLong()) }
        run()
        assertEquals(4L, app.config.callLogIdCursor())

        // Cleared and used again: ids restart below the cursor, and reading on
        // from it would return nothing for ever while reporting success.
        FakeCallLogProvider.calls = listOf(Call(number = "+52fresh", date = 99, id = 1))
        val dao = app.database.outbox()
        dao.delete(dao.take(OutboxKind.CALL_LOG, 1000, enrollment = ENROLMENT).map { it.id })

        assertEquals(ListenableWorker.Result.success(), run())

        assertEquals(1, queued())
        assertEquals(1L, app.config.callLogIdCursor())
    }

    @Test
    fun `the first pass on this build carries the old cursor across`() = runTest {
        reset()
        enroll()
        grantCallLog()
        // What the previous build left behind: a moment, not an id, and no id
        // cursor at all.
        app.config.setCallLogCursor(2_000)
        app.config.clearCallLogIdCursor()
        FakeCallLogProvider.calls = listOf(
            Call(number = "+52sent", date = 1_000, modified = 1_000),
            Call(number = "+52alsosent", date = 2_000, modified = 2_000),
            Call(number = "+52unsent", date = 3_000, modified = 3_000),
        )

        assertEquals(ListenableWorker.Result.success(), run())

        assertEquals(1, queued())
        assertEquals(3L, app.config.callLogIdCursor())
    }

    @Test
    fun `an unavailable provider leaves the cursor alone`() = runTest {
        reset()
        enroll()
        grantCallLog()
        FakeCallLogProvider.returnsNull = true

        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(0L, app.config.callLogIdCursor())
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

    // Three work names run this worker -- the periodic pass, the pass a call's
    // end asks for and the Sync now chain -- and nothing kept two of them
    // apart: both read from the same cursor and queued the same calls twice.
    @Test
    fun `two passes at once queue each call once`() = runTest {
        reset()
        enroll()
        grantCallLog()
        FakeCallLogProvider.calls = listOf(Call(number = "+525512345678", date = 10))
        val bothReading = CountDownLatch(2)
        FakeCallLogProvider.onRead = {
            bothReading.countDown()
            bothReading.await(OVERLAP_MILLIS, TimeUnit.MILLISECONDS)
        }

        coroutineScope { repeat(2) { launch(Dispatchers.IO) { run() } } }

        assertEquals(1, queued())
    }

    private companion object {
        const val OVERLAP_MILLIS = 500L

        // What enroll() makes it, spelled by Settings, not by this test.
        val ENROLMENT = Settings(baseUrl = "https://odoo.example.com", identifier = "phone-01")
            .enrollment!!
    }
}

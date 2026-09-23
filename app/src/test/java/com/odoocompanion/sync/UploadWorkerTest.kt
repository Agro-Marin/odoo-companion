package com.odoocompanion.sync

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.odoocompanion.CompanionApp
import com.odoocompanion.config.enrol
import com.odoocompanion.data.OutboxEntry
import com.odoocompanion.data.OutboxKind
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UploadWorkerTest {
    private val app: CompanionApp get() = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private suspend fun unenroll() = app.config.clearEnrollment()

    private suspend fun enroll() {
        app.config.enrol(
            server.url("/").toString().trimEnd('/'),
            "phone-01",
            "token",
        )
    }

    private val refusal = """{"error":"no_calls","message":"No call entries in payload"}"""

    private fun respond(code: Int, body: String = refusal) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse(code = code, body = body)
        }
    }

    private suspend fun queueCall() = app.database.outbox().insert(
        OutboxEntry(
            kind = OutboxKind.CALL_LOG,
            payload = """{"number":"+52","direction":"incoming","timestamp":1,"duration":1}""",
            createdAt = 1L,
        ),
    )

    private suspend fun runWorker(): ListenableWorker.Result =
        TestListenableWorkerBuilder<UploadWorker>(app).build().doWork()

    @Test
    fun `an unenrolled device succeeds without contacting anything`() = runTest {
        unenroll()
        queueCall()

        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a successful drain records the time and clears the last error`() = runTest {
        enroll()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse(body = """{"status":"success","accepted":1}""")
        }
        queueCall()

        assertEquals(ListenableWorker.Result.success(), runWorker())

        val settings = app.config.current()
        assertNull(settings.lastUploadError)
        assertTrue(settings.lastUploadAt > 0)
        assertEquals(settings.lastUploadAt, settings.lastAttemptAt)
    }

    // 503 is the link, not a verdict on the row: that is the path that still
    // asks WorkManager to retry. A 500 now defers the row and reports success.
    @Test
    fun `a failed drain records the attempt as well as the error`() = runTest {
        enroll()
        respond(503)
        queueCall()

        assertEquals(ListenableWorker.Result.retry(), runWorker())

        val settings = app.config.current()
        assertNotNull(settings.lastUploadError)
        assertTrue(settings.lastAttemptAt > settings.lastUploadAt)
    }

    @Test
    fun `a device missing from the server is retried, not written off`() = runTest {
        enroll()
        respond(404, """{"error":"endpoint_not_found"}""")
        queueCall()

        assertEquals(ListenableWorker.Result.retry(), runWorker())
        assertEquals(1, app.database.outbox().countOf(OutboxKind.CALL_LOG))
    }
}

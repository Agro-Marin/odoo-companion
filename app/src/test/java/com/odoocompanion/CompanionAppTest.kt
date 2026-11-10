package com.odoocompanion

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.odoocompanion.config.enrol
import com.odoocompanion.softphone.SoftphoneSettings
import com.odoocompanion.sync.SyncScheduler
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

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

    private fun liveIds(name: String) = WorkManager.getInstance(app)
        .getWorkInfosForUniqueWork(name)
        .get()
        .filterNot { it.state.isFinished }
        .map { it.id }

    // Measured on an emulator, moved off a server that did not answer: "Send
    // here" waited 34 s for an upload already backing off against the old one,
    // which KEEP kept -- and WorkManager's backoff reaches five hours, the
    // periodic upload's too. The backoff was earned by a link that is gone.
    @Test
    fun `a new enrolment does not wait out the backoff the old server earned`() = runTest {
        app.startup.join()
        app.config.enrol("https://old.example.com", "phone-01", "t")
        app.applyConfiguration()
        SyncScheduler.uploadNow(app, wifiOnlyUploads = false)
        val waiting = liveIds(SyncScheduler.UPLOAD_NOW)
        val periodic = liveIds(SyncScheduler.UPLOAD_PERIODIC)

        app.config.enrol("https://old.example.com", "phone-01", "rotated")
        app.applyConfiguration()
        assertEquals("a reconcile that moved nothing", waiting, liveIds(SyncScheduler.UPLOAD_NOW))
        assertEquals(periodic, liveIds(SyncScheduler.UPLOAD_PERIODIC))

        app.config.enrol("https://new.example.com", "phone-01", "t")
        app.applyConfiguration()
        val fresh = liveIds(SyncScheduler.UPLOAD_NOW)
        assertEquals(1, fresh.size)
        assertFalse("the old request was replaced", fresh.single() in waiting)
        assertFalse(liveIds(SyncScheduler.UPLOAD_PERIODIC).single() in periodic)
    }

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

    // The softphone asks Odoo for its extension, and that round trip ran inside
    // the reconcile: every save, policy push and boot waited out OkHttp's
    // timeouts. On an emulator whose server accepted and stalled, BOOT_COMPLETED
    // was held 60 s and the system declared the app not responding.
    @Test
    fun `a server that stalls does not hold the reconcile`() {
        val server = MockWebServer().apply { start() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder()
                .body("{}")
                .headersDelay(STALL_SECONDS, TimeUnit.SECONDS)
                .build()
        }
        try {
            runBlocking {
                app.startup.join()
                app.config.enrol(server.url("/").toString(), "phone-01", "token")

                val started = System.nanoTime()
                app.applyConfiguration()
                val tookMillis = (System.nanoTime() - started) / 1_000_000

                assertTrue("the reconcile took $tookMillis ms", tookMillis < 5_000)
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `the extension odoo names is still applied, after the reconcile`() {
        val server = MockWebServer().apply { start() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse(
                body = """{"service":"remote_mobile","status":"success",""" +
                    """"sip":{"username":"201","secret":"s","domain":"pbx.example"}}""",
            )
        }
        try {
            runBlocking {
                app.startup.join()
                app.softphones.save(null)
                app.config.enrol(server.url("/").toString(), "phone-01", "token")

                app.applyConfiguration()

                val stored = withTimeoutOrNull(10_000) {
                    var current = app.softphones.current()
                    while (current == null) {
                        delay(50)
                        current = app.softphones.current()
                    }
                    current
                }
                assertEquals(SoftphoneSettings("201", "s", "pbx.example"), stored)
            }
        } finally {
            server.close()
        }
    }

    // The lookup is answered after the reconcile, so the enrolment it asked
    // about may have changed by then: re-enrolled onto another server, the
    // first server's late answer must not decide the second one's line.
    @Test
    fun `a late answer about a changed enrolment is not applied`() {
        val first = MockWebServer().apply { start() }
        val second = MockWebServer().apply { start() }
        first.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder()
                .body(
                    """{"service":"remote_mobile","status":"success",""" +
                        """"sip":{"username":"201","secret":"s","domain":"pbx.example"}}""",
                )
                .headersDelay(2, TimeUnit.SECONDS)
                .build()
        }
        second.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse(
                code = 404,
                body = """{"service":"remote_mobile","error":"no_extension"}""",
            )
        }
        try {
            runBlocking {
                app.startup.join()
                app.softphones.save(null)
                app.config.enrol(first.url("/").toString(), "phone-01", "token")
                app.applyConfiguration()
                app.config.enrol(second.url("/").toString(), "phone-01", "token")
                app.applyConfiguration()

                // Watched throughout, not only at the end: the second server's
                // answer comes last and would win either way, and the defect is
                // the moment between -- the line registering on an extension
                // and server that are no longer this phone's.
                var heldTheFirstAnswer = false
                withTimeoutOrNull(5_000) {
                    while (true) {
                        if (app.softphones.current() != null) heldTheFirstAnswer = true
                        delay(10)
                    }
                }

                assertTrue("the first server was asked", first.requestCount > 0)
                assertTrue("the second server was asked", second.requestCount > 0)
                assertFalse("the first server's extension was applied", heldTheFirstAnswer)
                assertEquals(null, app.softphones.current())
            }
        } finally {
            first.close()
            second.close()
        }
    }

    private companion object {
        const val STALL_SECONDS = 20L
    }
}

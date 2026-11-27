package com.odoocompanion

import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import androidx.work.WorkManager
import com.odoocompanion.config.EnrollmentResult
import com.odoocompanion.config.enrol
import com.odoocompanion.config.form
import com.odoocompanion.data.DeadReason
import com.odoocompanion.data.OutboxEntry
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.push.PushBinding
import com.odoocompanion.push.PushRegistration
import com.odoocompanion.softphone.SoftphoneSettings
import com.odoocompanion.softphone.SoftphoneStore
import com.odoocompanion.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class CompanionAppTest {
    private val app: CompanionApp get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        ShadowLog.setLoggable("CompanionApp", Log.DEBUG)
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

    @Test
    fun `a save the screen stops waiting for is still reconciled`() = runBlocking {
        app.startup.join()
        assertTrue(liveIds(SyncScheduler.CALL_LOG_PERIODIC).isEmpty())

        val saving = app.saveForm(form(callLogEnabled = true))
        val screen = launch(Dispatchers.Default) { saving.await() }
        screen.cancel()

        assertEquals(EnrollmentResult.Saved, saving.await())
        assertTrue("the settings were stored", app.config.current().isEnrolled)
        assertEquals(
            "and the reconcile ran behind them",
            1,
            liveIds(SyncScheduler.CALL_LOG_PERIODIC).size,
        )
    }

    @Test
    fun `new credentials do not wait out the backoff the old ones earned`() = runTest {
        app.config.enrol("https://old.example.com", "phone-01", "t")
        app.applyConfiguration()
        SyncScheduler.uploadNow(app, wifiOnlyUploads = false)
        var waiting = liveIds(SyncScheduler.UPLOAD_NOW)
        var periodic = liveIds(SyncScheduler.UPLOAD_PERIODIC)

        app.applyConfiguration()
        assertEquals("a reconcile that moved nothing", waiting, liveIds(SyncScheduler.UPLOAD_NOW))
        assertEquals(periodic, liveIds(SyncScheduler.UPLOAD_PERIODIC))

        val links = listOf(
            "https://old.example.com" to "rotated",
            "https://new.example.com" to "t",
        )
        for ((url, token) in links) {
            app.config.enrol(url, "phone-01", token)
            app.applyConfiguration()
            val fresh = liveIds(SyncScheduler.UPLOAD_NOW)
            assertEquals(1, fresh.size)
            assertFalse("$url/$token: the old request was replaced", fresh.single() in waiting)
            assertFalse(liveIds(SyncScheduler.UPLOAD_PERIODIC).single() in periodic)
            waiting = fresh
            periodic = liveIds(SyncScheduler.UPLOAD_PERIODIC)
        }
    }

    @Test
    fun `a failed queue recovery allows scheduling and retries on the next reconcile`() = runTest {
        app.startup.join()
        val dao = app.database.outbox()
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.LOCATION,
                payload = "{}",
                createdAt = 1,
                deadAt = 2,
                deadReason = DeadReason.UNDECODABLE,
            ),
        )
        app.config.onBuildChanged(-1L) {}
        app.config.setWifiOnlyUploads(true)
        withContext(Dispatchers.IO) {
            app.database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER refuse_recovery BEFORE UPDATE OF deadAt ON outbox " +
                    "BEGIN SELECT RAISE(ABORT, 'temporary storage failure'); END",
            )
        }
        try {
            app.applyConfiguration()
            assertEquals(NetworkType.UNMETERED, periodicUploadNetwork())
            assertEquals(1, dao.countDead())
        } finally {
            withContext(Dispatchers.IO) {
                app.database.openHelper.writableDatabase.execSQL("DROP TRIGGER refuse_recovery")
            }
        }

        app.applyConfiguration()
        assertEquals(0, dao.countDead())
        assertEquals(1, dao.countOf(OutboxKind.LOCATION))
    }

    @Test
    fun `startup keeps the stored wifi-only constraint`() = runTest {
        app.config.setWifiOnlyUploads(true)

        app.applyConfiguration()

        assertEquals(NetworkType.UNMETERED, periodicUploadNetwork())
    }

    @Test
    fun `startup applies a metered-allowed setting just as faithfully`() = runTest {
        app.config.setWifiOnlyUploads(false)

        app.applyConfiguration()

        assertEquals(NetworkType.CONNECTED, periodicUploadNetwork())
    }

    @Test
    fun `concurrent reconfigurations settle on the last word, not a stale one`() = runTest {
        app.config.setWifiOnlyUploads(false)

        val passes = (1..6).map { async { app.applyConfiguration() } }
        app.config.setWifiOnlyUploads(true)
        passes.awaitAll()
        app.applyConfiguration()

        assertEquals(NetworkType.UNMETERED, periodicUploadNetwork())
    }

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
    fun `a push lookup the link failed is asked again once a delivery gets through`() {
        val server = MockWebServer().apply { start() }
        val asks = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                !request.target.endsWith("/push") -> MockResponse(
                    code = 404,
                    body = """{"service":"remote_mobile","error":"no_extension","message":"x"}""",
                )

                asks.incrementAndGet() == 1 -> MockResponse(code = 503)

                else -> MockResponse(
                    body = """{"service":"remote_mobile","status":"success",""" +
                        """"vapid_public_key":"v"}""",
                )
            }
        }
        suspend fun asked(count: Int) = withTimeoutOrNull(10_000) {
            while (asks.get() < count) delay(20)
        } != null
        try {
            runBlocking {
                app.startup.join()
                app.config.enrol(server.url("/").toString(), "phone-01", "token")
                app.applyConfiguration()
                assertTrue("the first lookup met the outage", asked(1))

                val retried = withTimeoutOrNull(10_000) {
                    while (asks.get() < 2) {
                        app.retryLookupsAfterDelivery(System.currentTimeMillis())
                        delay(50)
                    }
                } != null
                assertTrue("a delivery asks again", retried)

                app.retryLookupsAfterDelivery(System.currentTimeMillis())
                delay(500)
                assertEquals("an answered lookup is not asked again", 2, asks.get())
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
                app.softphones.clear()
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

    @Test
    fun `only the active distributor instance may register or clear an endpoint`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val posts = java.util.concurrent.CopyOnWriteArrayList<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "POST") posts += request.target
                    return MockResponse(
                        body = """{"service":"remote_mobile","vapid_public_key":"v"}"""
                    )
                }
            }
            app.config.enrol(server.url("/").toString(), "phone", "token")
            val owner = requireNotNull(app.config.current().enrollment)
            val binding = PushBinding(app)
            val old = binding.activate("old-enrollment") {}
            val current = binding.activate(owner) {}
            assertEquals(current, PushBinding(app).activate(owner) {})

            val endpoint = PushRegistration("https://push.example/one", "test", "test")
            app.reportPushEndpoint(old, endpoint).join()
            app.reportPushEndpoint(old, null).join()
            assertTrue(posts.isEmpty())

            app.reportPushEndpoint(current, endpoint).join()
            binding.disable {}
            app.reportPushEndpoint(current, null).join()
            assertEquals(listOf("/remote/mobile/phone/push"), posts)
        }
    }

    @Test
    fun `unscoped credentials from an older installation wait for a fresh lookup`() = runTest {
        app.config.enrol("https://sip.example", "phone", "token")
        val owner = app.config.current()
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(app.cacheDir, "legacy.preferences_pb")
        }
        data.edit {
            it[stringPreferencesKey("username")] = "201"
            it[stringPreferencesKey("secret")] = "test"
            it[stringPreferencesKey("domain")] = "pbx.example"
        }
        val store = SoftphoneStore(data, app.config)
        assertEquals(null, store.current(owner))
        val fresh = SoftphoneSettings("202", "test", "pbx.example")
        store.save(owner, fresh)
        assertEquals(fresh, store.current())
    }

    @Test
    fun `a stored extension can only run under the enrollment that supplied it`() = runBlocking {
        app.config.enrol("https://old.example", "phone", "token")
        val old = app.config.current()
        val credentials = SoftphoneSettings("201", "s", "pbx.example")
        app.softphones.save(old, credentials)
        app.config.enrol("https://old.example", "phone", "rotated")
        assertEquals(credentials, app.softphones.current())

        app.config.enrol("https://new.example", "phone", "token")
        app.softphones.save(old, credentials)
        assertEquals(null, app.softphones.current())
    }

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
                app.softphones.clear()
                app.config.enrol(first.url("/").toString(), "phone-01", "token")
                app.applyConfiguration()
                app.config.enrol(second.url("/").toString(), "phone-01", "token")
                app.applyConfiguration()

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

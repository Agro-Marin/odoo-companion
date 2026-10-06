package com.odoocompanion.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.odoocompanion.config.Settings
import com.odoocompanion.data.CompanionDatabase
import com.odoocompanion.data.DeadReason
import com.odoocompanion.data.OutboxDao
import com.odoocompanion.data.OutboxEntry
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.net.OdooClient
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LinkFaultTest {
    private lateinit var database: CompanionDatabase
    private lateinit var dao: OutboxDao
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CompanionDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.outbox()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        database.close()
    }

    private fun drainer() = OutboxDrainer(dao, OdooClient(), { 5_000L }) {
        Settings(
            baseUrl = server.url("/").toString().trimEnd('/'),
            identifier = "phone-01",
            token = "t",
        )
    }

    private fun deadReason(): String? = database.query("SELECT deadReason FROM outbox", null)
        .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun answer(code: Int, body: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse(code = code, body = body)
        }
    }

    // A 503, a 401 or a dead socket on the positions answers for the calls and
    // the recordings too: they go to the same host with the same token. Sending
    // them anyway charged each one an attempt per pass, and a recording streamed
    // its whole body into a server that was not going to take it.
    @Test
    fun `a link fault on the positions sends nothing else in the same pass`() = runTest {
        answer(503, """{"error":"unavailable"}""")
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.LOCATION,
                payload = """{"latitude":19.4,"longitude":-99.1,"timestamp":1}""",
                createdAt = 1,
            ),
        )
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.CALL_LOG,
                payload = """{"number":"+525500000001","direction":"incoming",""" +
                    """"timestamp":1,"duration":1}""",
                createdAt = 1,
            ),
        )

        val report = drainer().drainAll()

        assertEquals(OutboxDrainer.Outcome.RETRY, report.outcome)
        assertEquals("one request, the one the link refused", 1, server.requestCount)
        assertEquals(1, dao.take(OutboxKind.LOCATION, 1).single().attempts)
        assertEquals(
            "the call was never sent, so it is not charged",
            0,
            dao.take(OutboxKind.CALL_LOG, 1).single().attempts,
        )
    }

    private suspend fun queueOneOfEach() {
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.LOCATION,
                payload = """{"latitude":19.4,"longitude":-99.1,"timestamp":2}""",
                createdAt = 2,
            ),
        )
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.CALL_LOG,
                payload = """{"number":"+525500000002","direction":"incoming",""" +
                    """"timestamp":2,"duration":1}""",
                createdAt = 2,
            ),
        )
    }

    private fun answerByRoute(location: MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.url.encodedPath.endsWith("/location")) {
                    location
                } else {
                    MockResponse(
                        body = """{"service":"remote_mobile","status":"success","accepted":1}""",
                    )
                }
        }
    }

    // A gateway timing out one slow request, or a worker killed by the memory
    // limit while ingesting it, says nothing about the next request: the calls
    // still go, as they did before a link fault ended the pass.
    @Test
    fun `a gateway timeout on the positions does not hold the calls back`() = runTest {
        answerByRoute(MockResponse(code = 504, body = "<html>Gateway Time-out</html>"))
        queueOneOfEach()

        val report = drainer().drainAll()

        assertEquals(OutboxDrainer.Outcome.RETRY, report.outcome)
        assertEquals(mapOf(OutboxKind.CALL_LOG to 1), report.accepted)
        assertEquals(0, dao.countOf(OutboxKind.CALL_LOG))
    }

    @Test
    fun `a refused token on the positions holds the calls back`() = runTest {
        answerByRoute(
            MockResponse(
                code = 401,
                body = """{"service":"remote_mobile","error":"authentication_failed"}""",
            ),
        )
        queueOneOfEach()

        val report = drainer().drainAll()

        assertEquals(1, server.requestCount)
        assertEquals(0, dao.take(OutboxKind.CALL_LOG, 1).single().attempts)
        assertTrue(report.accepted.isEmpty())
    }

    @Test
    fun `a captive portal on the positions holds the calls back`() = runTest {
        answerByRoute(MockResponse(body = "<html>Sign in to the hotel Wi-Fi</html>"))
        queueOneOfEach()

        drainer().drainAll()

        assertEquals(1, server.requestCount)
        assertEquals(0, dao.take(OutboxKind.CALL_LOG, 1).single().attempts)
    }

    @Test
    fun `a rotated token alone, with no positions flowing, never retires a call`() = runTest {
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.CALL_LOG,
                payload = """{"number":"+5255000","direction":"incoming",""" +
                    """"timestamp":1,"duration":1}""",
                createdAt = 1L,
            ),
        )
        answer(401, """{"error":"authentication_failed","message":"Invalid credentials"}""")
        val drainer = drainer()
        val reasons = mutableListOf<String?>()
        repeat(OutboxDrainer.MAX_ATTEMPTS + 3 * (OutboxDrainer.MAX_REVIVALS + 1) + 5) {
            drainer.drainAll()
            reasons += deadReason()
        }
        assertTrue(
            "a 401 is the link's fault, so the row cycles between budget-dead and one probe",
            reasons.last() == DeadReason.BUDGET || reasons.last() == null,
        )
        assertEquals(emptyList<String?>(), reasons.filter { it == DeadReason.REFUSED })
    }
}

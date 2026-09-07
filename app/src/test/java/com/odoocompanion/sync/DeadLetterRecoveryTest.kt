package com.odoocompanion.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.odoocompanion.config.Settings
import com.odoocompanion.data.CompanionDatabase
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
class DeadLetterRecoveryTest {
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

    private val refusal = """{"error":"no_calls","message":"No call entries in payload"}"""

    private fun answer(code: Int, body: String = refusal) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse(code = code, body = body)
        }
    }

    private suspend fun queueCall(id: Int = 0) = dao.insert(
        OutboxEntry(
            kind = OutboxKind.CALL_LOG,
            payload = """{"number":"+5255000$id","direction":"incoming",""" +
                """"timestamp":$id,"duration":1}""",
            createdAt = id.toLong(),
        ),
    )

    @Test
    fun `a fixed enrollment puts back what only ran out of retries`() = runTest {
        queueCall()
        answer(404)
        val drainer = drainer()
        repeat(OutboxDrainer.MAX_ATTEMPTS + 1) { drainer.drainAll() }
        assertEquals(1, dao.countDead())
        assertEquals(0, dao.countOf(OutboxKind.CALL_LOG))

        answer(200, """{"status":"success","accepted":1}""")
        val recovered = drainer.drainAll()

        assertEquals(1, recovered.revived)

        assertEquals(OutboxDrainer.Outcome.DONE, drainer.drainAll().outcome)
        assertEquals(0, dao.countDead())
        assertEquals(0, dao.countOf(OutboxKind.CALL_LOG))
    }

    @Test
    fun `a refused row is not resurrected by a later success`() = runTest {
        queueCall(1)
        answer(400)
        drainer().drainAll()
        assertEquals(1, dao.countDead())

        queueCall(2)
        answer(200, """{"status":"success","accepted":1}""")
        val report = drainer().drainAll()

        assertEquals(0, report.revived)
        assertEquals(1, dao.countDead())
    }

    @Test
    fun `a row this build cannot read waits for a build that can`() = runTest {
        answer(200, """{"status":"success","accepted":1}""")
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.CALL_LOG,
                payload = "this is not json",
                createdAt = 1L,
            ),
        )

        val report = drainer().drainAll()

        assertEquals(1, report.undecodable[OutboxKind.CALL_LOG])
        assertEquals(1, dao.countDead())

        assertEquals(0, dao.reviveExhausted(attempts = 24))
        assertEquals(1, dao.countDead())

        assertEquals(1, dao.reviveUndecodable())
        assertEquals(0, dao.countDead())
        assertEquals(1, dao.countOf(OutboxKind.CALL_LOG))
    }

    @Test
    fun `a drain that sent nothing releases one probe, not the queue`() = runTest {
        repeat(3) { queueCall(it) }
        answer(503)
        val drainer = drainer()
        repeat(OutboxDrainer.MAX_ATTEMPTS + 1) { drainer.drainAll() }
        assertEquals(3, dao.countDead())

        val idle = drainer.drainAll()

        assertEquals(OutboxDrainer.REVIVAL_PROBE, idle.revived)
        assertEquals(2, dao.countDead())
    }

    @Test
    fun `the pass that expires a row does not immediately probe with it`() = runTest {
        queueCall()
        answer(503)
        val drainer = drainer()
        repeat(OutboxDrainer.MAX_ATTEMPTS + 1) { drainer.drainAll() }

        assertEquals(1, dao.countDead())
        assertEquals(0, dao.countOf(OutboxKind.CALL_LOG))
    }

    @Test
    fun `an exhausted position is discarded, not counted as undeliverable`() = runTest {
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.LOCATION,
                payload = """{"latitude":1.0,"longitude":2.0,"timestamp":1}""",
                createdAt = 1L,
            ),
        )
        answer(503)
        val drainer = drainer()
        repeat(OutboxDrainer.MAX_ATTEMPTS + 1) { drainer.drainAll() }

        assertEquals(0, dao.countDead())
        assertEquals(0, dao.countOf(OutboxKind.LOCATION))
        assertEquals(0, dao.countIncludingDead(OutboxKind.LOCATION))
    }

    @Test
    fun `a revived row gets one probe attempt, not a fresh budget`() = runTest {
        queueCall()
        answer(401)
        val drainer = drainer()
        repeat(OutboxDrainer.MAX_ATTEMPTS + 1) { drainer.drainAll() }
        answer(200, """{"status":"success","accepted":1}""")
        drainer.drainAll()

        val revived = dao.take(OutboxKind.CALL_LOG, 1)
        assertTrue(
            "a revival tests delivery once; it does not spend 25 more requests proving it",
            revived.all { it.attempts == OutboxDrainer.ATTEMPTS_AFTER_REVIVAL },
        )
        assertTrue("and the revival is counted", revived.all { it.revivals == 1 })
    }

    @Test
    fun `a row the server always chokes on stops being revived and becomes visible`() = runTest {
        queueCall()
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.LOCATION,
                payload = """{"latitude":1.0,"longitude":2.0,"timestamp":1}""",
                createdAt = 1L,
            ),
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.target.endsWith("calllog")) {
                    MockResponse(code = 500, body = """{"error":"processing_error"}""")
                } else {
                    MockResponse(body = """{"status":"success","accepted":1}""")
                }
        }
        val drainer = drainer()

        var revivals = 0
        var retired = 0
        repeat(200) {
            dao.insert(
                OutboxEntry(
                    kind = OutboxKind.LOCATION,
                    payload = """{"latitude":1.0,"longitude":2.0,"timestamp":$it}""",
                    createdAt = it.toLong(),
                ),
            )
            val report = drainer.drainAll()
            revivals += report.revived
            retired += report.unreachable
        }

        assertEquals(
            "the revival budget is spent once, not for ever",
            OutboxDrainer.MAX_REVIVALS,
            revivals,
        )
        assertEquals("and the row is then on the status screen", 1, dao.countDead())
        assertEquals(
            "and it is reported as retired for good, not as one more budget death",
            1,
            retired,
        )
        assertEquals(0, dao.reviveExhausted(attempts = OutboxDrainer.ATTEMPTS_AFTER_REVIVAL))
    }
}

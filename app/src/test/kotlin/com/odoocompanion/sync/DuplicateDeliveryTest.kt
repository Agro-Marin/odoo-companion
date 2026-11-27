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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DuplicateDeliveryTest {
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

    private fun settings() = Settings(
        baseUrl = server.url("/").toString().trimEnd('/'),
        identifier = "phone-01",
        token = "t",
    )

    private fun drainer() = OutboxDrainer(dao, OdooClient(), { 1_000L }) { settings() }

    private fun answer(code: Int, body: String) {
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
    fun `a batch the server already held leaves the queue as a delivery`() = runTest {
        answer(200, """{"status":"success","accepted":0,"duplicates":1,"skipped":0}""")
        queueCall()

        val report = drainer().drainAll()

        assertEquals(OutboxDrainer.Outcome.DONE, report.outcome)
        assertEquals(0, dao.countOf(OutboxKind.CALL_LOG))
        assertEquals(0, dao.countDead())
        assertEquals(1, report.duplicates[OutboxKind.CALL_LOG])
        assertNull(report.skipped[OutboxKind.CALL_LOG])
    }

    @Test
    fun `a partial retry counts the duplicates apart from the skipped`() = runTest {
        answer(200, """{"status":"success","accepted":1,"duplicates":1,"skipped":1}""")
        queueCall(1)
        queueCall(2)
        queueCall(3)

        val report = drainer().drainAll()

        assertEquals(1, report.duplicates[OutboxKind.CALL_LOG])
        assertEquals(1, report.skipped[OutboxKind.CALL_LOG])
        assertEquals(0, dao.countOf(OutboxKind.CALL_LOG))
    }

    @Test
    fun `an older server's 422 for a batch it holds is still a delivery`() = runTest {
        answer(422, """{"status":"success","accepted":0,"duplicates":2,"skipped":0}""")
        queueCall(1)
        queueCall(2)

        val report = drainer().drainAll()

        assertEquals(OutboxDrainer.Outcome.DONE, report.outcome)
        assertEquals(0, dao.countDead())
        assertEquals(2, report.duplicates[OutboxKind.CALL_LOG])
    }

    @Test
    fun `a 422 with nothing accounted for is still a refusal`() = runTest {
        answer(422, """{"status":"success","accepted":0,"duplicates":0,"skipped":1}""")
        queueCall()

        val report = drainer().drainAll()

        assertEquals(1, dao.countDead())
        assertEquals(1, report.skipped[OutboxKind.CALL_LOG])
    }

    @Test
    fun `a 422 counting both duplicates and skipped is not read as delivery`() = runTest {
        answer(422, """{"status":"success","accepted":0,"duplicates":1,"skipped":1}""")
        queueCall(1)
        queueCall(2)

        drainer().drainAll()

        assertEquals(2, dao.countDead())
    }

    @Test
    fun `a 400 stays a refusal whatever its body says`() = runTest {
        answer(400, """{"error":"no_calls","duplicates":9,"status":"success"}""")
        queueCall()

        drainer().drainAll()

        assertEquals(1, dao.countDead())
    }
}

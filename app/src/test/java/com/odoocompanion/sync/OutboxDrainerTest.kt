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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OutboxDrainerTest {
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

    private fun drainer() = OutboxDrainer(dao, OdooClient()) { settings() }

    private fun alwaysAccept(delayMillis: Long = 0) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder()
                .body("""{"status":"success","accepted":1}""")
                .bodyDelay(delayMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
        }
    }

    private suspend fun queueCalls(count: Int) {
        dao.insertAll(
            (0 until count).map { i ->
                OutboxEntry(
                    kind = OutboxKind.CALL_LOG,
                    payload = """{"number":"+52551234567$i","direction":"1",""" +
                        """"timestamp":$i,"duration":30}""",
                    createdAt = i.toLong(),
                )
            },
        )
    }

    @Test
    fun `concurrent drains never send a row twice`() = runTest {
        alwaysAccept(delayMillis = 50)
        queueCalls(3)

        listOf(
            async { drainer().drainAll() },
            async { drainer().drainAll() },
            async { drainer().drainAll() },
        ).awaitAll()

        val sent = generateSequence { server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS) }
            .flatMap { recorded ->
                Json.parseToJsonElement(recorded.body!!.utf8())
                    .jsonObject["calls"]!!
                    .jsonArray
                    .map { it.jsonObject["number"]!!.toString() }
                    .asSequence()
            }
            .toList()

        assertEquals(3, sent.size)
        assertEquals(sent.size, sent.toSet().size)
        assertEquals(0, dao.countOf(OutboxKind.CALL_LOG))
    }

    @Test
    fun `a successful drain empties the queue`() = runTest {
        alwaysAccept()
        queueCalls(5)

        assertEquals(OutboxDrainer.Outcome.DONE, drainer().drainAll().outcome)

        assertEquals(0, dao.countOf(OutboxKind.CALL_LOG))
    }

    @Test
    fun `a server error keeps the rows and reports retry`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse(code = 500)
        }
        queueCalls(2)

        assertEquals(OutboxDrainer.Outcome.RETRY, drainer().drainAll().outcome)

        assertEquals(2, dao.countOf(OutboxKind.CALL_LOG))
        assertEquals(1, dao.take(OutboxKind.CALL_LOG, 10).first().attempts)
    }

    @Test
    fun `a payload the server refuses is dropped instead of blocking the queue`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse(
                code = 422,
                body = """{"error":"no_calls","message":"No call entries in payload"}""",
            )
        }
        queueCalls(2)

        assertEquals(OutboxDrainer.Outcome.DONE, drainer().drainAll().outcome)

        assertEquals(0, dao.countOf(OutboxKind.CALL_LOG))
    }

    @Test
    fun `an unreadable row is dropped alone, without blocking the queue`() = runTest {
        alwaysAccept()
        dao.insert(
            OutboxEntry(kind = OutboxKind.CALL_LOG, payload = "{not json", createdAt = 1L),
        )
        queueCalls(2)
        val drainer = drainer()

        val report = drainer.drainAll()

        assertEquals(OutboxDrainer.Outcome.DONE, report.outcome)

        assertEquals(0, dao.countOf(OutboxKind.CALL_LOG))

        assertEquals(1, report.undecodable[OutboxKind.CALL_LOG])
        assertNull(report.skipped[OutboxKind.CALL_LOG])
        assertEquals(1, dao.countDead())
    }

    @Test
    fun `the readable rows of a batch are still delivered`() = runTest {
        alwaysAccept()
        dao.insert(
            OutboxEntry(kind = OutboxKind.CALL_LOG, payload = "{not json", createdAt = 1L),
        )
        queueCalls(4)

        drainer().drainAll()

        val delivered = generateSequence {
            server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)
        }.flatMap {
            Json.parseToJsonElement(it.body!!.utf8()).jsonObject["calls"]!!.jsonArray.asSequence()
        }.toList()
        assertEquals(4, delivered.size)
    }

    @Test
    fun `an unreadable configuration is never charged to the queue`() = runTest {
        alwaysAccept()
        queueCalls(2)

        val failing = OutboxDrainer(dao, OdooClient()) { error("configuration unavailable") }

        assertEquals(OutboxDrainer.Outcome.RETRY, failing.drainAll().outcome)

        assertEquals(2, dao.countOf(OutboxKind.CALL_LOG))
        assertEquals(0, dao.take(OutboxKind.CALL_LOG, 1).first().attempts)

        repeat(OutboxDrainer.MAX_ATTEMPTS + 1) { failing.drainAll() }

        assertEquals(2, dao.countOf(OutboxKind.CALL_LOG))
        assertEquals(0, dao.countDead())
    }

    @Test
    fun `rows the server could not store are counted rather than lost quietly`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse(
                body = """{"status":"success","accepted":1,"skipped":1}""",
            )
        }
        dao.insertAll(
            listOf(
                OutboxEntry(
                    kind = OutboxKind.LOCATION,
                    payload = """{"latitude":1.0,"longitude":2.0,"timestamp":1}""",
                    createdAt = 1,
                ),
                OutboxEntry(
                    kind = OutboxKind.LOCATION,
                    payload = """{"latitude":2.0,"longitude":3.0,"timestamp":2}""",
                    createdAt = 2,
                ),
            ),
        )
        val drainer = drainer()

        val report = drainer.drainAll()

        assertEquals(OutboxDrainer.Outcome.DONE, report.outcome)
        assertEquals(0, dao.countOf(OutboxKind.LOCATION))
        assertEquals(1, report.skipped[OutboxKind.LOCATION])
    }

    @Test
    fun `a batch the server stored nothing from is counted in full`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse(
                code = 422,
                body = """{"error":"no_calls","message":"No call entries in payload"}""",
            )
        }
        queueCalls(3)
        val drainer = drainer()

        val report = drainer.drainAll()

        assertEquals(OutboxDrainer.Outcome.DONE, report.outcome)
        assertEquals(0, dao.countOf(OutboxKind.CALL_LOG))
        assertEquals(3, report.skipped[OutboxKind.CALL_LOG])

        assertEquals(3, dao.countDead())
    }

    @Test
    fun `a fully accepted drain reports nothing skipped`() = runTest {
        alwaysAccept()
        queueCalls(2)
        val drainer = drainer()

        assertEquals(true, drainer.drainAll().skipped.isEmpty())
    }

    @Test
    fun `a recording whose file vanished is discarded, not retried forever`() = runTest {
        alwaysAccept()
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.RECORDING,
                payload = RECORDING_PAYLOAD,
                filePath = "/nonexistent/gone.m4a",
                createdAt = 1L,
            ),
        )

        assertEquals(OutboxDrainer.Outcome.DONE, drainer().drainAll().outcome)

        assertEquals(0, dao.countOf(OutboxKind.RECORDING))
    }

    private companion object {
        const val RECORDING_PAYLOAD =
            """{"number":"+52551234","recorded_at":1,"file_name":"a.m4a","mimetype":"audio/mp4"}"""
    }

    @Test
    fun `a drain that loses the lock returns the thread instead of queueing behind it`() =
        runBlocking {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    Thread.sleep(400)
                    return MockResponse(body = """{"status":"success","accepted":1}""")
                }
            }
            repeat(6) {
                dao.insert(
                    OutboxEntry(
                        kind = OutboxKind.CALL_LOG,
                        payload = """{"number":"+1$it","direction":"incoming",""" +
                            """"timestamp":$it,"duration":1}""",
                        createdAt = it.toLong(),
                    ),
                )
            }

            // upload-periodic, upload-now and the tail of the collect-now chain
            // are three separate WorkManager items, all running UploadWorker,
            // against a pool of max(2, min(cpus - 1, 4)).
            val runs = withContext(Dispatchers.IO) {
                (1..3).map {
                    async {
                        val begin = System.currentTimeMillis()
                        val report = drainer().drainAll()
                        report to System.currentTimeMillis() - begin
                    }
                }.awaitAll()
            }
            val elapsed = runs.map { it.second }.sorted()

            assertEquals(
                "two of three must say they stood down, not merely finish quickly -- " +
                    "a timing assertion alone passes on a fast machine for the wrong reason",
                2,
                runs.count { it.first.deferred },
            )

            assertEquals("the queue is still emptied", 0, dao.countOf(OutboxKind.CALL_LOG))
            assertEquals(
                "and the six rows still cross the wire as one batch, once",
                1,
                server.requestCount,
            )
            assertTrue(
                "two of three did no work; they must not hold a worker thread for " +
                    "the whole of the third: ${elapsed[1]} ms against ${elapsed[2]} ms",
                elapsed[1] * 2 < elapsed[2],
            )
        }

    @Test
    fun `a row queued mid-drain is not left until the next period`() = runTest {
        var late = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.target.endsWith("calllog") && !late) {
                    late = true
                    // The drain does locations, then calls, then recordings, so a
                    // call arriving now has already missed its turn.
                    runBlocking {
                        dao.insert(
                            OutboxEntry(
                                kind = OutboxKind.CALL_LOG,
                                payload = """{"number":"+99","direction":"incoming",""" +
                                    """"timestamp":99,"duration":1}""",
                                createdAt = 99L,
                            ),
                        )
                    }
                }
                return MockResponse(body = """{"status":"success","accepted":1}""")
            }
        }
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.CALL_LOG,
                payload = """{"number":"+1","direction":"incoming","timestamp":1,"duration":1}""",
                createdAt = 1L,
            ),
        )

        val report = drainer().drainAll()

        assertEquals(OutboxDrainer.Outcome.DONE, report.outcome)
        assertEquals(1, dao.countOf(OutboxKind.CALL_LOG))
        assertTrue(
            "the winner owns the remainder, so a loser can bail without losing work",
            report.moreWorkPending,
        )
    }
}

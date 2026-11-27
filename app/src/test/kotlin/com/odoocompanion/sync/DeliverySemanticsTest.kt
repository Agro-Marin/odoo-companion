package com.odoocompanion.sync

import android.app.Application
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.odoocompanion.config.PAYLOAD_LIMIT_TTL_MILLIS
import com.odoocompanion.config.Settings
import com.odoocompanion.data.CompanionDatabase
import com.odoocompanion.data.DeadReason
import com.odoocompanion.data.FileStamp
import com.odoocompanion.data.OutboxDao
import com.odoocompanion.data.OutboxEntry
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.data.OutboxLimits
import com.odoocompanion.net.OdooClient
import com.odoocompanion.whatsapp.DocumentSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DeliverySemanticsTest {
    private lateinit var database: CompanionDatabase
    private lateinit var dao: OutboxDao
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        ShadowLog.setLoggable("OutboxDrainer", Log.DEBUG)
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

    private fun drainer(now: () -> Long = { 1_000L }) =
        OutboxDrainer(dao, OdooClient(), now) { settings() }

    private var learnedCap = 0L
    private var learnedAt = 0L
    private var clock = 1_000L

    private fun capAwareDrainer() = OutboxDrainer(
        dao,
        OdooClient(),
        { clock },
        learnPayloadLimit = { bytes, at ->
            learnedCap = bytes
            learnedAt = at
        },
    ) { settings().copy(maxPayloadBytes = learnedCap, maxPayloadLearnedAt = learnedAt) }

    private val refusal = """{"error":"no_calls","message":"No call entries in payload"}"""

    private fun respond(code: Int, body: String = refusal) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse(code = code, body = body)
        }
    }

    private fun accept(body: String = """{"status":"success","accepted":1}""") {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse(body = body)
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
    fun `learning the service name applies to the next request in the same drain`() = runTest {
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.LOCATION,
                payload = """{"latitude":1.0,"longitude":2.0,"timestamp":1}""",
                createdAt = 1,
            ),
        )
        queueCall()
        server.enqueue(MockResponse(body = """{"service":"remote_mobile","status":"success"}"""))
        server.enqueue(MockResponse(body = """{"status":"success","accepted":1}"""))

        val report = drainer().drainAll()

        assertEquals(2, server.requestCount)
        assertEquals(0, dao.countOf(OutboxKind.LOCATION))
        assertEquals(1, dao.countOf(OutboxKind.CALL_LOG))
        assertEquals(OutboxDrainer.Outcome.RETRY, report.outcome)
    }

    @Test
    fun `a failed capability write preserves delivery and retries learning across drains`() =
        runTest {
            var writes = 0
            val client = OdooClient(onServerNamedItself = {
                if (++writes == 1) throw IOException("storage unavailable")
            })
            val drainer = OutboxDrainer(dao, client) { settings() }
            val named = """{"service":"remote_mobile","status":"success","accepted":1}"""
            queueCall(1)
            respond(200, named)
            assertTrue(drainer.drainAll().delivered)
            assertEquals(0, dao.countOf(OutboxKind.CALL_LOG))

            queueCall(2)
            respond(200, """{"status":"success","accepted":1}""")
            assertFalse(drainer.drainAll().delivered)
            assertEquals(1, dao.countOf(OutboxKind.CALL_LOG))
            respond(200, named)
            assertTrue(drainer.drainAll().delivered)
            assertEquals(2, writes)
            assertEquals(3, server.requestCount)
        }

    @Test
    fun `transport exception detail stays out of upload status and Android logs`() = runTest {
        val detail = "private document filename"
        val http = OkHttpClient.Builder().addInterceptor { throw IOException(detail) }.build()
        queueCall()

        val report = OutboxDrainer(dao, OdooClient(http)) { settings() }.drainAll()

        assertEquals(OutboxDrainer.Outcome.RETRY, report.outcome)
        assertFalse(report.lastError!!.contains(detail))
        assertFalse(
            ShadowLog.getLogs().any {
                detail in it.msg.orEmpty() || detail in it.throwable?.stackTraceToString().orEmpty()
            }
        )
    }

    @Test
    fun `a 404 keeps the queue so a re-enrolled device still has its calls`() = runTest {
        respond(404, """{"error":"endpoint_not_found"}""")
        queueCall()

        val report = drainer().drainAll()

        assertEquals(OutboxDrainer.Outcome.RETRY, report.outcome)
        assertEquals(1, dao.countOf(OutboxKind.CALL_LOG))
        assertEquals(0, dao.countDead())
        assertTrue(report.lastError!!.contains("archived"))
    }

    @Test
    fun `a 413 keeps the payload because the cap is a server setting`() = runTest {
        respond(413, """{"error":"payload_too_large"}""")
        queueCall()

        val report = drainer().drainAll()

        assertEquals(
            "a verdict on the row is not a fault of the link to wait out",
            OutboxDrainer.Outcome.DONE,
            report.outcome,
        )
        assertEquals(1, dao.countOf(OutboxKind.CALL_LOG))
        assertEquals("it waits on its own retryAfter", 0, dao.countOf(OutboxKind.CALL_LOG, 1_000L))
    }

    @Test
    fun `an unrecognised status is retried rather than treated as a refusal`() = runTest {
        respond(418)
        queueCall()

        assertEquals(OutboxDrainer.Outcome.RETRY, drainer().drainAll().outcome)
        assertEquals(1, dao.countOf(OutboxKind.CALL_LOG))
    }

    @Test
    fun `a duplicate leaves the queue counted as delivered, not as skipped`() = runTest {
        respond(409, """{"error":"duplicate_event"}""")
        queueCall()

        val report = drainer().drainAll()

        assertEquals(OutboxDrainer.Outcome.DONE, report.outcome)
        assertEquals(0, dao.countOf(OutboxKind.CALL_LOG))
        assertEquals(0, dao.countDead())
        assertEquals(1, report.duplicates[OutboxKind.CALL_LOG])
        assertTrue(report.skipped.isEmpty())
    }

    @Test
    fun `a 422 sets the call aside where it can be counted`() = runTest {
        respond(422)
        queueCall()

        val report = drainer().drainAll()

        assertEquals(OutboxDrainer.Outcome.DONE, report.outcome)
        assertEquals(0, dao.countOf(OutboxKind.CALL_LOG))
        assertEquals(1, dao.countDead())
        assertEquals(1, report.skipped[OutboxKind.CALL_LOG])
    }

    @Test
    fun `a gateway answering 200 with its own error envelope keeps the calls`() = runTest {
        respond(200, """{"error":"blocked_by_policy","message":"not allowed"}""")
        queueCall(1)
        queueCall(2)

        val report = drainer().drainAll()

        assertEquals(OutboxDrainer.Outcome.RETRY, report.outcome)
        assertEquals(
            "a JSON body is not proof that Odoo answered",
            2,
            dao.countOf(OutboxKind.CALL_LOG)
        )
        assertEquals(0, dao.countDead())
        assertTrue(report.accepted.isEmpty())
    }

    @Test
    fun `a 200 accounting for nothing does not revive what the budget gave up on`() = runTest {
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.CALL_LOG,
                payload = """{"number":"+1","direction":"incoming","timestamp":1,"duration":1}""",
                createdAt = 1L,
                attempts = OutboxDrainer.MAX_ATTEMPTS,
            ),
        )
        respond(200, "{}")
        queueCall(2)

        val report = drainer().drainAll()

        assertEquals("an empty object is not a delivery", 0, report.revived)
        assertFalse(report.delivered)
        assertEquals(1, dao.countOf(OutboxKind.CALL_LOG))
    }

    @Test
    fun `a drain the system stops does not spend the retry budget`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                Thread.sleep(3_000)
                return MockResponse(body = """{"status":"success","accepted":1}""")
            }
        }
        queueCall(1)

        val job = CoroutineScope(Dispatchers.IO).async { drainer().drainAll() }
        delay(500)
        job.cancel()
        delay(400)

        val rows = dao.take(OutboxKind.CALL_LOG, 10)
        assertEquals(
            "WorkManager stopping the worker is not the server refusing the payload",
            listOf(0),
            rows.map { it.attempts },
        )
        assertEquals(listOf(null), rows.map { it.lastError })
    }

    @Test
    fun `a recording whose audio is gone is counted, not dropped in silence`() = runTest {
        accept()
        val gone = File.createTempFile("rec", ".m4a").also { it.delete() }
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.RECORDING,
                payload = """{"recorded_at":1,"file_name":"a.m4a","mimetype":"audio/mp4"}""",
                filePath = gone.absolutePath,
                createdAt = 1L,
            ),
        )

        val report = drainer().drainAll()

        assertEquals("the phone lost the file; the operator gets to know", 1, report.lost)
        assertEquals(0, dao.countOf(OutboxKind.RECORDING))
        assertEquals(0, dao.countDead())
    }

    @Test
    fun `a batch over the server's cap is narrowed rather than retried unchanged`() = runTest {
        var narrowedTo = Int.MAX_VALUE
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val calls = request.body!!.utf8().split("\"number\"").size - 1
                if (calls > 2) {
                    return MockResponse(
                        code = 413,
                        body = """{"error":"payload_too_large",""" +
                            """"message":"Request exceeds maximum size of 64KB"}""",
                    )
                }
                narrowedTo = minOf(narrowedTo, calls)
                return MockResponse(body = """{"status":"success","accepted":$calls}""")
            }
        }
        repeat(8) { queueCall(it) }

        val report = drainer().drainAll()

        assertEquals(OutboxDrainer.Outcome.DONE, report.outcome)
        assertEquals("everything still gets there", 0, dao.countOf(OutboxKind.CALL_LOG))
        assertTrue("the batch was narrowed, not retried unchanged", narrowedTo <= 2)
        assertEquals(0, dao.countDead())
    }

    @Test
    fun `a deferred call does not inflate the next narrowed batch`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body!!.utf8()
                val count = body.split("\"number\"").size - 1
                return if (count > 1 || "+52550000" in body) {
                    MockResponse(code = 413, body = """{"error":"payload_too_large"}""")
                } else {
                    MockResponse(body = """{"status":"success","accepted":1}""")
                }
            }
        }
        repeat(5) { queueCall(it) }

        val report = drainer().drainAll()

        assertEquals(4, report.accepted[OutboxKind.CALL_LOG])
        assertEquals(1, dao.take(OutboxKind.CALL_LOG, 5).single().attempts)
    }

    @Test
    fun `a 500 narrows the batch to the row that raises, not the whole batch`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body!!.utf8()
                if ("+52550003" in body) {
                    return MockResponse(code = 500, body = """{"error":"processing_error"}""")
                }
                val calls = body.split("\"number\"").size - 1
                return MockResponse(body = """{"status":"success","accepted":$calls}""")
            }
        }
        repeat(8) { queueCall(it) }

        val report = drainer().drainAll()

        assertEquals(OutboxDrainer.Outcome.DONE, report.outcome)
        val left = dao.take(OutboxKind.CALL_LOG, 100)
        assertEquals("only the row the server chokes on is still queued", 1, left.size)
        assertEquals(1, left.single().attempts)
        assertTrue("and it alone carries the server's verdict", left.single().serverFault)
    }

    @Test
    fun `once the raising row is isolated the rest go at full batch size`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body!!.utf8()
                if ("+52550000\"" in body) {
                    return MockResponse(code = 500, body = """{"error":"processing_error"}""")
                }
                val calls = body.split("\"number\"").size - 1
                return MockResponse(body = """{"status":"success","accepted":$calls}""")
            }
        }
        repeat(OutboxDrainer.CALL_LOG_BATCH) { queueCall(it) }

        val report = drainer().drainAll()

        assertEquals(OutboxDrainer.CALL_LOG_BATCH - 1, report.accepted[OutboxKind.CALL_LOG])
        assertFalse(report.moreWorkPending)
        assertTrue("${server.requestCount} requests", server.requestCount <= 9)
    }

    @Test
    fun `a 503 is the link, so the batch is charged as one and not narrowed`() = runTest {
        var requests = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests++
                return MockResponse(code = 503, body = """{"error":"unavailable"}""")
            }
        }
        repeat(8) { queueCall(it) }

        drainer().drainAll()

        assertEquals(1, requests)
        assertTrue(dao.take(OutboxKind.CALL_LOG, 100).none { it.serverFault })
    }

    @Test
    fun `a cap learned a day ago is tried again rather than trusted for ever`() = runTest {
        respond(200, """{"status":"success","recording_id":1}""")
        queueRecording(bytes = 4_000)
        val stale = OutboxDrainer(dao, OdooClient(), { 5_000L + PAYLOAD_LIMIT_TTL_MILLIS }) {
            settings().copy(maxPayloadBytes = 1_000, maxPayloadLearnedAt = 4_000L)
        }

        val report = stale.drainAll()

        assertEquals(1, server.requestCount)
        assertEquals(1, report.accepted[OutboxKind.RECORDING])
    }

    @Test
    fun `a success that states the cap teaches it before any 413`() = runTest {
        respond(200, """{"status":"success","accepted":1,"max_payload_bytes":1048576}""")
        var learned = 0L
        queueCall(1)

        OutboxDrainer(dao, OdooClient(), {
            5_000L
        }, { bytes, _ -> learned = bytes }, settingsProvider = ::settings).drainAll()

        assertEquals(1_048_576L, learned)
    }

    @Test
    fun `a 413 that states its cap in bytes is read exactly`() = runTest {
        respond(413, """{"error":"payload_too_large","message":"x","limit_bytes":1500}""")
        var learned = 0L
        queueCall(1)

        OutboxDrainer(dao, OdooClient(), {
            5_000L
        }, { bytes, _ -> learned = bytes }, settingsProvider = ::settings).drainAll()

        assertEquals(1500L, learned)
    }

    @Test
    fun `a recording over what any mobile device accepts is kept, charged and bounded`() = runTest {
        val audio = File.createTempFile("huge", ".m4a").apply { deleteOnExit() }
        RandomAccessFile(audio, "rw").use { it.setLength(OutboxLimits.MAX_FILE_BYTES + 1) }
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.RECORDING,
                payload = """{"recorded_at":1,"file_name":"huge.m4a","mimetype":"audio/mp4"}""",
                filePath = audio.absolutePath,
                createdAt = audio.lastModified(),
            ),
        )

        val report = drainer().drainAll()

        assertEquals(OutboxDrainer.Outcome.DONE, report.outcome)
        assertEquals(0, server.requestCount)
        val row = dao.take(OutboxKind.RECORDING, 1).single()
        assertEquals("kept, not refused: the bound on revivals settles it", 1, row.attempts)
        assertTrue(row.serverFault)
        assertTrue(row.retryAfter > 1_000L)
        assertTrue(audio.exists())
        audio.delete()
    }

    @Test
    fun `a server that says which rows it could not store sets aside exactly those`() = runTest {
        respond(
            200,
            """{"status":"success","accepted":2,"duplicates":0,"skipped":1,"skipped_indexes":[1]}""",
        )
        repeat(3) { queueCall(it) }

        val report = drainer().drainAll()

        assertEquals(OutboxDrainer.Outcome.DONE, report.outcome)
        assertEquals(1, report.skipped[OutboxKind.CALL_LOG])
        assertEquals("the two it stored are gone", 0, dao.countOf(OutboxKind.CALL_LOG))
        assertEquals("the one it could not store is kept where it is counted", 1, dao.countDead())
        assertTrue(dao.take(OutboxKind.CALL_LOG, 10).isEmpty())
    }

    @Test
    fun `an entire skipped batch is retained without reporting delivery with or without indexes`() =
        runTest {
            for (indexes in listOf("", ""","skipped_indexes":[1,0,1]""")) {
                repeat(2) { queueCall(it) }
                accept("""{"status":"success","accepted":0,"skipped":2$indexes}""")
                val report = drainer().drainAll()
                assertFalse(report.delivered)
                assertEquals(2, report.skipped[OutboxKind.CALL_LOG])
                assertEquals(2, report.deadLettered)
            }
            assertEquals(4, dao.countDead())
        }

    @Test
    fun `a server that only says how many it skipped is believed as before`() = runTest {
        respond(200, """{"status":"success","accepted":2,"duplicates":0,"skipped":1}""")
        repeat(3) { queueCall(it) }

        val report = drainer().drainAll()

        assertEquals(1, report.skipped[OutboxKind.CALL_LOG])
        assertEquals(0, dao.countOf(OutboxKind.CALL_LOG))
        assertEquals(0, dao.countDead())
    }

    @Test
    fun `a recording the server chokes on does not hold the ones behind it`() = runTest {
        val poison = queueRecording(fill = 1)
        val fine = queueRecording()
        val poisonAudio = java.util.Base64.getEncoder().encodeToString(poison.readBytes())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.body!!.utf8().contains(poisonAudio)) {
                    MockResponse(code = 500, body = """{"error":"processing_error"}""")
                } else {
                    MockResponse(body = """{"status":"success","recording_id":1}""")
                }
        }

        val report = drainer().drainAll()

        assertEquals(OutboxDrainer.Outcome.DONE, report.outcome)
        assertEquals(1, report.accepted[OutboxKind.RECORDING])
        assertFalse("the good one is uploaded and gone", fine.exists())
        assertTrue("the bad one is kept and charged", poison.exists())
        assertEquals(1, dao.take(OutboxKind.RECORDING, 10).single().attempts)
        poison.delete()
    }

    @Test
    fun `a single row over the cap says what the cap is`() = runTest {
        respond(
            413,
            """{"error":"payload_too_large","message":"Request exceeds maximum size of 64KB"}""",
        )
        queueCall(1)

        val report = drainer().drainAll()

        assertEquals(OutboxDrainer.Outcome.DONE, report.outcome)
        assertEquals(
            "raising the cap is an operator action, so the call is kept",
            1,
            dao.countOf(OutboxKind.CALL_LOG)
        )
        assertTrue(
            "the server told us the number; put it where an operator reads it",
            report.lastError!!.contains("64 KB"),
        )
    }

    @Test
    fun `a recording over the declared cap is not pushed once per retry`() = runTest {
        var pushedBytes = 0L
        var recordingPosts = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!request.target.endsWith("recording")) {
                    return MockResponse(
                        body = """{"status":"success","accepted":1,"max_payload_bytes":1048576}""",
                    )
                }
                recordingPosts++
                pushedBytes += request.bodySize
                return MockResponse(
                    code = 413,
                    body = """{"error":"payload_too_large",""" +
                        """"message":"Request exceeds maximum size of 1024KB"}""",
                )
            }
        }
        val audio = File.createTempFile("rec", ".m4a")
        audio.writeBytes(ByteArray(2 * 1024 * 1024))
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.RECORDING,
                payload = """{"recorded_at":1,"file_name":"a.m4a","mimetype":"audio/mp4"}""",
                filePath = audio.absolutePath,
                createdAt = audio.lastModified(),
            ),
        )

        var pass = 0
        while (dao.countOf(OutboxKind.RECORDING) > 0 || dao.countDead() == 0) {
            dao.insert(
                OutboxEntry(
                    kind = OutboxKind.LOCATION,
                    payload = """{"latitude":1.0,"longitude":2.0,"timestamp":$pass}""",
                    createdAt = pass.toLong(),
                ),
            )
            clock += OutboxDrainer.ROW_BACKOFF_MAX_MILLIS + 1
            capAwareDrainer().drainAll()
            if (++pass > 200) break
        }

        assertEquals("it must end up refused, not cycling", 1, dao.countDead())
        assertTrue(
            "the cap is re-probed once per revival, not spent on all 25 attempts: " +
                "$recordingPosts posts",
            recordingPosts <= OutboxDrainer.MAX_REVIVALS + 1,
        )
        assertTrue(
            "and the handset must not push a fleet's data allowance at a wall: " +
                "${pushedBytes / 1024 / 1024} MB",
            pushedBytes < 16L * 1024 * 1024,
        )
        audio.delete()
    }

    @Test
    fun `a captive portal answering 200 does not empty the queue`() = runTest {
        respond(200, "<html><body>Sign in to use this WiFi</body></html>")
        queueCall(1)
        queueCall(2)

        val report = drainer().drainAll()

        assertEquals(OutboxDrainer.Outcome.RETRY, report.outcome)
        assertEquals(2, dao.countOf(OutboxKind.CALL_LOG))
        assertEquals(0, dao.countDead())
        assertTrue(report.accepted.isEmpty())
    }

    @Test
    fun `a proxy answering 400 does not dead-letter the calls it never carried`() = runTest {
        respond(400, "<html>Request blocked</html>")
        queueCall(1)

        val report = drainer().drainAll()

        assertEquals(OutboxDrainer.Outcome.RETRY, report.outcome)
        assertEquals(1, dao.countOf(OutboxKind.CALL_LOG))
        assertEquals(0, dao.countDead())
    }

    @Test
    fun `a proxy answering 409 does not look like the server already holding it`() = runTest {
        respond(409, "<html>Conflict</html>")
        queueCall(1)

        val report = drainer().drainAll()

        assertEquals(OutboxDrainer.Outcome.RETRY, report.outcome)
        assertEquals(1, dao.countOf(OutboxKind.CALL_LOG))
        assertTrue(report.duplicates.isEmpty())
    }

    @Test
    fun `a drain stopped by its batch budget asks for another pass`() = runTest {
        accept()
        val batches = OutboxDrainer.BATCHES_PER_DRAIN + 1
        repeat(batches * OutboxDrainer.CALL_LOG_BATCH) { queueCall(it) }

        val report = drainer().drainAll()

        assertEquals(OutboxDrainer.Outcome.DONE, report.outcome)
        assertTrue("the pass must say it stopped early", report.moreWorkPending)
        assertEquals(null, report.lastError)
        assertEquals(OutboxDrainer.CALL_LOG_BATCH, dao.countOf(OutboxKind.CALL_LOG))
    }

    @Test
    fun `an unstorable position is discarded rather than kept forever`() = runTest {
        respond(422)
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.LOCATION,
                payload = """{"latitude":1.0,"longitude":2.0,"timestamp":1}""",
                createdAt = 1L,
            ),
        )

        drainer().drainAll()

        assertEquals(0, dao.countOf(OutboxKind.LOCATION))
        assertEquals(0, dao.countDead())
    }

    private suspend fun queueRecording(bytes: Int = 32, fill: Byte = 0): File {
        val audio = File.createTempFile("rec", ".m4a")
        if (fill == 0.toByte()) {
            RandomAccessFile(audio, "rw").use { it.setLength(bytes.toLong()) }
        } else {
            audio.writeBytes(ByteArray(bytes) { fill })
        }
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.RECORDING,
                payload = """{"number":"+52","recorded_at":1,""" +
                    """"file_name":"a.m4a","mimetype":"audio/mp4"}""",
                filePath = audio.absolutePath,
                createdAt = audio.lastModified(),
            ),
        )
        return audio
    }

    @Test
    fun `a legacy recording withdrawn before its file stamp is saved stays on disk`() = runTest {
        ShadowLog.setLoggable("OutboxDrainer", Log.DEBUG)
        val audio = queueRecording()
        try {
            accept()
            val withdrawing = object : OutboxDao by dao {
                override suspend fun take(
                    kind: String,
                    limit: Int,
                    now: Long,
                    enrollment: String?
                ): List<OutboxEntry> {
                    val entries = dao.take(kind, limit, now, enrollment)
                    if (kind == OutboxKind.RECORDING && entries.isNotEmpty()) {
                        dao.markDeadIds(entries.map { it.id }, now, null, DeadReason.WITHDRAWN)
                    }
                    return entries
                }
            }
            val report = OutboxDrainer(withdrawing, OdooClient(), {
                clock
            }) { settings() }.drainAll()
            assertFalse(report.delivered)
            assertEquals(0, server.requestCount)
            assertTrue(audio.exists())
            assertEquals(1, dao.countDead())
        } finally {
            audio.delete()
        }
    }

    @Test
    fun `an uploaded recording takes its audio off the phone`() = runTest {
        accept()
        val audio = queueRecording()

        drainer().drainAll()

        assertEquals(0, dao.countOf(OutboxKind.RECORDING))
        assertFalse("the file should be gone", audio.exists())
    }

    @Test
    fun `a recording the server already held is a duplicate, not a second acceptance`() = runTest {
        accept(
            """{"service":"remote_mobile","status":"success","recording_id":7,""" +
                """"matched":true,"duplicates":1}""",
        )
        val audio = queueRecording()

        val report = drainer().drainAll()

        assertEquals(mapOf(OutboxKind.RECORDING to 1), report.duplicates)
        assertTrue(report.accepted.isEmpty())
        assertTrue(report.delivered)
        assertFalse("the server has it, so the phone does not keep it", audio.exists())
    }

    @Test
    fun `a set-aside recording keeps its audio until the row is purged`() = runTest {
        respond(422)
        val audio = queueRecording()

        drainer().drainAll()

        assertEquals(1, dao.countDead())
        assertTrue("audio must survive while the row does", audio.exists())

        val later = 1_000L + OutboxLimits.DEAD_RETENTION_MILLIS + 1
        drainer(now = { later }).drainAll()

        assertEquals(0, dao.countDead())
        assertFalse("purging must not orphan the audio", audio.exists())
    }

    @Test
    fun `a recording backlog larger than one batch drains in a single pass`() = runTest {
        accept()
        val files = (0 until 10).map { queueRecording() }

        val report = drainer().drainAll()

        assertEquals(OutboxDrainer.Outcome.DONE, report.outcome)
        assertEquals(0, dao.countOf(OutboxKind.RECORDING))
        assertFalse(report.moreWorkPending)
        files.forEach { it.delete() }
    }

    @Test
    fun `a pass stopped by the size budget asks for another without reporting an error`() =
        runTest {
            server.bodyLimit = 0
            accept()
            val big = (OutboxDrainer.FILE_BYTES_PER_DRAIN / 2 + 1).toInt()
            val files = listOf(queueRecording(big), queueRecording(big), queueRecording(big))

            val report = drainer().drainAll()

            assertTrue("the budget should have stopped the pass", report.moreWorkPending)
            assertEquals(OutboxDrainer.Outcome.DONE, report.outcome)
            assertEquals(null, report.lastError)
            assertTrue(dao.countOf(OutboxKind.RECORDING) > 0)
            files.forEach { it.delete() }
        }

    @Test
    fun `documents and recordings share one size budget per pass`() = runTest {
        server.bodyLimit = 0
        accept()
        val big = (OutboxDrainer.FILE_BYTES_PER_DRAIN / 2 + 1).toInt()
        val document = File.createTempFile("doc", ".pdf").apply {
            RandomAccessFile(this, "rw").use { it.setLength(big.toLong()) }
        }
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.DOCUMENT,
                payload = """{"file_name":"d.pdf","mimetype":"application/pdf"}""",
                filePath = document.absolutePath,
                createdAt = document.lastModified(),
                source = FileStamp.read(
                    document
                )!!.copy(sha256 = DocumentSnapshot.digest(document, document.length())),
            ),
        )
        val files = listOf(queueRecording(big), queueRecording(big))

        val report = drainer().drainAll()

        assertTrue(report.moreWorkPending)
        assertEquals(2, server.requestCount)
        assertEquals(1, dao.countOf(OutboxKind.RECORDING))
        (files + document).forEach { it.delete() }
    }

    @Test
    fun `an unreadable configuration does not spend the delivery retry budget`() = runTest {
        queueCall(1)
        queueCall(2)
        val broken = OutboxDrainer(dao, OdooClient(), { 1_000L }) { error("no config") }

        val report = broken.drainAll()

        assertEquals(OutboxDrainer.Outcome.RETRY, report.outcome)
        assertEquals("device configuration unavailable (IllegalStateException)", report.lastError)
        assertEquals(listOf(0, 0), dao.take(OutboxKind.CALL_LOG, 10).map { it.attempts })
    }

    @Test
    fun `setting a record aside does not report the drain as failed`() = runTest {
        var refuse = true
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val refused = refuse && request.target.endsWith("calllog")
                return if (refused) {
                    MockResponse(code = 422, body = refusal)
                } else {
                    MockResponse(body = """{"status":"success","accepted":1}""")
                }
            }
        }
        queueCall()
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.LOCATION,
                payload = """{"latitude":1.0,"longitude":2.0,"timestamp":1}""",
                createdAt = 1L,
            ),
        )

        val report = drainer().drainAll()

        assertEquals(OutboxDrainer.Outcome.DONE, report.outcome)
        assertEquals(null, report.lastError)
        assertEquals(1, report.skipped[OutboxKind.CALL_LOG])
        assertEquals(1, dao.countDead())
        refuse = false
    }

    @Test
    fun `a delivered recording that will not delete is kept as dead, not forgotten`() = runTest {
        val locked = java.nio.file.Files.createTempDirectory("locked").toFile()
        val file = File(locked, "rec.m4a").apply {
            writeBytes(ByteArray(32))
            assertTrue(setLastModified(900))
        }
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.RECORDING,
                payload = """{"number":"+52","recorded_at":1,""" +
                    """"file_name":"a.m4a","mimetype":"audio/mp4"}""",
                filePath = file.absolutePath,
                createdAt = file.lastModified(),
            ),
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse(body = """{"status":"success","recording_id":1}""")
        }
        locked.setWritable(false)
        org.junit.Assume.assumeFalse("root deletes regardless; nothing to test", locked.canWrite())
        try {
            val report = drainer().drainAll()

            assertEquals(1, report.accepted[OutboxKind.RECORDING])
            assertTrue("the file is still there", file.exists())
            assertEquals(0, dao.countOf(OutboxKind.RECORDING))
            assertEquals(1, dao.countDead())
            assertTrue(file.absolutePath in dao.filesQueued(OutboxKind.RECORDING))
        } finally {
            locked.setWritable(true)
            locked.deleteRecursively()
        }
    }

    @Test
    fun `a recording this build cannot decode is set aside as undecodable`() = runTest {
        accept("""{"status":"success","recording_id":1}""")
        val unreadable = File.createTempFile("rec", ".m4a").apply { writeBytes(ByteArray(32)) }
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.RECORDING,
                payload = "{written by a newer build",
                filePath = unreadable.absolutePath,
                createdAt = unreadable.lastModified(),
            ),
        )
        val fine = queueRecording()

        val report = drainer().drainAll()

        assertEquals(OutboxDrainer.Outcome.DONE, report.outcome)
        assertEquals(1, report.undecodable[OutboxKind.RECORDING])
        assertFalse("the one behind it is not held up", fine.exists())
        assertTrue("its audio waits for a build that can read the row", unreadable.exists())
        assertEquals("a newer build puts it back", 1, dao.reviveUndecodable())
        unreadable.delete()
    }

    @Test
    fun `a recording over the declared cap does not hold the ones behind it`() = runTest {
        accept("""{"status":"success","recording_id":1}""")
        learnedCap = 1024
        val big = File.createTempFile("big", ".m4a").apply { writeBytes(ByteArray(4096)) }
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.RECORDING,
                payload = """{"recorded_at":1,"file_name":"b.m4a","mimetype":"audio/mp4"}""",
                filePath = big.absolutePath,
                createdAt = big.lastModified(),
            ),
        )
        val fine = queueRecording()

        val report = capAwareDrainer().drainAll()

        assertEquals(OutboxDrainer.Outcome.DONE, report.outcome)
        assertEquals(1, report.accepted[OutboxKind.RECORDING])
        assertFalse("the one behind it is uploaded", fine.exists())
        assertEquals("the oversized one is not sent", 1, server.requestCount)
        val kept = dao.take(OutboxKind.RECORDING, 10).single()
        assertEquals(1, kept.attempts)
        assertTrue("it waits on its own clock", kept.retryAfter > 1_000L)
        assertTrue(report.lastError!!.contains("1 KB"))
        big.delete()
    }

    @Test
    fun `a cap the server states on a success applies to the recordings of the same pass`() =
        runTest {
            var recordingPosts = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.target.endsWith("recording")) recordingPosts++
                    return MockResponse(
                        body = """{"status":"success","accepted":1,"max_payload_bytes":1024}""",
                    )
                }
            }
            queueCall(1)
            val big = queueRecording(bytes = 4096)

            drainer().drainAll()

            assertEquals("the call's reply already said it would not fit", 0, recordingPosts)
            assertTrue(big.exists())
            big.delete()
        }

    @Test
    fun `a dialer reusing a recording path while Odoo replies keeps the new audio`() = runTest {
        val root = java.nio.file.Files.createTempDirectory("recording-reuse").toFile()
        try {
            val audio = File(root, "Recordings/Call/call.m4a")
            audio.parentFile!!.mkdirs()
            audio.writeBytes(byteArrayOf(1, 2, 3))
            audio.setLastModified(100_000L)
            val harvest = com.odoocompanion.recording.RecordingHarvest(
                dao,
                root,
                durationOf = { null },
                now = { 1_000_000L },
            )
            assertEquals(1, harvest.queueNew())
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    audio.writeBytes(byteArrayOf(4, 5, 6))
                    audio.setLastModified(200_000L)
                    return MockResponse(body = """{"status":"success","accepted":1}""")
                }
            }

            val report = drainer().drainAll()
            assertEquals(1, report.accepted[OutboxKind.RECORDING])
            assertTrue(audio.isFile)
            assertEquals(listOf<Byte>(4, 5, 6), audio.readBytes().toList())
            assertEquals(1, harvest.queueNew())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a replacement discovered before the first v9 drain gets its own metadata`() = runTest {
        val root = java.nio.file.Files.createTempDirectory("legacy-reuse").toFile()
        try {
            val audio = File(root, "Recordings/Call/call.m4a")
            audio.parentFile!!.mkdirs()
            audio.writeBytes(byteArrayOf(5, 6))
            assertTrue(audio.setLastModified(20_000))
            dao.insert(
                OutboxEntry(
                    kind = OutboxKind.RECORDING,
                    filePath = audio.absolutePath,
                    payload = """{"recorded_at":1,"file_name":"old.m4a","mimetype":"audio/mp4"}""",
                    createdAt = 10_000,
                ),
            )
            val harvest = com.odoocompanion.recording.RecordingHarvest(
                dao,
                root,
                durationOf = { null },
                now = { 100_000 },
            )
            assertEquals(1, harvest.queueNew())
            assertEquals(0, harvest.queueNew())
            accept()
            val report = drainer().drainAll()
            assertEquals(1, report.lost)
            assertEquals(1, report.accepted[OutboxKind.RECORDING])
            assertEquals(1, server.requestCount)
            val sent = server.takeRequest().body!!.utf8()
            val metadata = Json.parseToJsonElement(sent).jsonObject
            assertEquals("call.m4a", metadata.getValue("file_name").jsonPrimitive.content)
            assertFalse(audio.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `purging a legacy row leaves audio replaced between harvest and refusal`() = runTest {
        val file = queueRecording()
        try {
            val row = dao.take(OutboxKind.RECORDING, 1).single()
            val refusedAt = row.createdAt + 1_000
            dao.markDeadIds(listOf(row.id), refusedAt, "refused")
            file.writeBytes(byteArrayOf(11))
            assertTrue(file.setLastModified(row.createdAt + 500))
            val report = drainer(now = {
                refusedAt + 1 + OutboxLimits.DEAD_RETENTION_MILLIS
            }).drainAll()
            assertEquals(1, report.purged)
            assertEquals(listOf<Byte>(11), file.readBytes().toList())
            assertEquals(0, server.requestCount)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a failed payload limit write preserves acknowledgements and is retried`() = runTest {
        val first = queueRecording()
        val second = queueRecording()
        try {
            accept("""{"status":"success","accepted":1,"max_payload_bytes":52428800}""")
            var writes = 0
            val drainer = OutboxDrainer(
                dao,
                OdooClient(),
                learnPayloadLimit = { _, _ ->
                    if (++writes == 1) throw IOException("settings unavailable")
                },
                settingsProvider = ::settings,
            )
            val report = drainer.drainAll()
            assertEquals(2, report.accepted[OutboxKind.RECORDING])
            assertEquals(2, writes)
            assertFalse(first.exists())
            assertFalse(second.exists())
            drainer.drainAll()
            assertEquals(2, server.requestCount)
        } finally {
            first.delete()
            second.delete()
        }
    }

    @Test
    fun `a purged recording whose audio will not delete stays remembered`() = runTest {
        val locked = java.nio.file.Files.createTempDirectory("locked").toFile()
        val file = File(locked, "rec.m4a").apply {
            writeBytes(ByteArray(32))
            assertTrue(setLastModified(900))
        }
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.RECORDING,
                payload = """{"recorded_at":1,"file_name":"a.m4a","mimetype":"audio/mp4"}""",
                filePath = file.absolutePath,
                createdAt = file.lastModified(),
            ),
        )
        dao.markDeadIds(
            dao.take(OutboxKind.RECORDING, 1).map { it.id },
            1_000L,
            "uploaded, but the file could not be deleted",
            com.odoocompanion.data.DeadReason.KEPT_ON_DISK,
        )
        locked.setWritable(false)
        org.junit.Assume.assumeFalse("root deletes regardless; nothing to test", locked.canWrite())
        try {
            val later = 1_000L + OutboxLimits.DEAD_RETENTION_MILLIS + 1
            drainer(now = { later }).drainAll()

            assertTrue(file.exists())
            assertTrue(
                "forgetting it would harvest and upload it again",
                file.absolutePath in dao.filesQueued(OutboxKind.RECORDING),
            )
        } finally {
            locked.setWritable(true)
            locked.deleteRecursively()
        }
    }

    @Test
    fun `a drain that defers one row still reports the delivery beside the error`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.target.endsWith("recording")) {
                    MockResponse(code = 500, body = """{"error":"processing_error"}""")
                } else {
                    MockResponse(body = """{"status":"success","accepted":1}""")
                }
        }
        queueCall(1)
        val audio = queueRecording()

        val report = drainer().drainAll()

        assertTrue(report.delivered)
        assertEquals("server 500", report.lastError)
        audio.delete()
    }

    @Test
    fun `a cap stated on every success is stored once per drain, not once per batch`() = runTest {
        accept("""{"status":"success","accepted":200,"max_payload_bytes":52428800}""")
        dao.insertAll(
            (0 until 2 * OutboxDrainer.LOCATION_BATCH).map {
                OutboxEntry(
                    kind = OutboxKind.LOCATION,
                    payload = """{"latitude":1.0,"longitude":2.0,"timestamp":$it}""",
                    createdAt = it.toLong(),
                )
            },
        )
        var writes = 0

        OutboxDrainer(dao, OdooClient(), {
            1_000L
        }, { _, _ -> writes++ }, settingsProvider = ::settings).drainAll()

        assertEquals(2, server.requestCount)
        assertEquals(1, writes)
    }

    @Test
    fun `an index the server repeats sets its row aside once`() = runTest {
        respond(
            200,
            """{"status":"success","accepted":1,"duplicates":0,"skipped":1,"skipped_indexes":[1,1]}""",
        )
        repeat(2) { queueCall(it) }

        val report = drainer().drainAll()

        assertEquals(1, report.skipped[OutboxKind.CALL_LOG])
        assertEquals(1, report.deadLettered)
    }
}

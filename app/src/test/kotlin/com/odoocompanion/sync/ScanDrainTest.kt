package com.odoocompanion.sync

import android.app.Application
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.odoocompanion.config.Settings
import com.odoocompanion.data.CompanionDatabase
import com.odoocompanion.data.DeadReason
import com.odoocompanion.data.OutboxDao
import com.odoocompanion.data.OutboxEntry
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.data.OutboxLimits
import com.odoocompanion.data.QueuedFile
import com.odoocompanion.data.UploadState
import com.odoocompanion.net.OdooClient
import com.odoocompanion.scan.Scan
import com.odoocompanion.scan.ScanIntake
import com.odoocompanion.scan.ScanLog
import com.odoocompanion.ui.ScanPanel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.IOException
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ScanDrainTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: CompanionDatabase
    private lateinit var dao: OutboxDao
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        ShadowLog.setLoggable("OutboxDrainer", Log.DEBUG)
        ShadowLog.setLoggable("FileCleanup", Log.DEBUG)
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

    private val history by lazy { ScanLog(File(folder.root, "history.json")) }

    private fun drainer(
        remember: suspend (String, List<QueuedFile>) -> Unit = { _, states ->
            history.remember(states)
        },
        now: () -> Long = { 1_000L },
    ) = OutboxDrainer(
        dao,
        OdooClient(),
        now,
        rememberFiles = remember,
    ) {
        Settings(
            baseUrl = server.url("/").toString().trimEnd('/'),
            identifier = "phone-01",
            token = "t",
        )
    }

    private fun respond(code: Int, body: String) {
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
    }

    private suspend fun queueScan(name: String = "scan.pdf"): File {
        val pdf = folder.newFile(name).apply { writeBytes(PDF) }
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.SCAN,
                payload = """{"file_name":"Escaneo.pdf","mimetype":"application/pdf",""" +
                    """"source":"Scanner","received_at":1}""",
                filePath = pdf.absolutePath,
                createdAt = 1L,
            ),
        )
        return pdf
    }

    @Test
    fun `a scan goes to document under file_b64, and its copy is deleted`() = runTest {
        respond(200, """{"service":"remote_mobile","status":"success","document_id":9}""")
        val pdf = queueScan()

        history.add(Scan(pdf.path, pdf.name, "Scanner", 1L))
        val report = drainer().drainAll()
        val reopened = ScanLog(File(folder.root, "history.json")).apply { open() }
        assertEquals(
            UploadState.DELIVERED,
            ScanPanel.stateOf(reopened.scans.value!!.single(), emptyMap())
        )

        val request = server.takeRequest()
        assertEquals("/remote/mobile/phone-01/document", request.url.encodedPath)
        val sent = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals(
            PDF.toList(),
            Base64.getDecoder().decode(sent.getValue("file_b64").jsonPrimitive.content).toList(),
        )
        assertEquals("Escaneo.pdf", sent.getValue("file_name").jsonPrimitive.content)
        assertEquals("Scanner", sent.getValue("source").jsonPrimitive.content)
        assertTrue(report.delivered)
        assertEquals(0, dao.countOf(OutboxKind.SCAN))
        assertFalse("the app's copy is the outbox's to delete", pdf.exists())
        assertTrue(report.deliveredDocuments.isEmpty())
    }

    @Test
    fun `a missing scan keeps its gone status in the documents card`() = runTest {
        val file = queueScan()
        history.add(Scan(file.path, file.name, "Scanner", 1L))
        assertTrue(file.delete())

        val report = drainer().drainAll()

        assertEquals(1, report.lost)
        assertEquals(0, server.requestCount)
        assertEquals(DeadReason.GONE, dao.fileStates(OutboxKind.SCAN).first().single().deadReason)
        drainer { 1_001L + OutboxLimits.DEAD_RETENTION_MILLIS }.drainAll()
        val reopened = ScanLog(File(folder.root, "history.json")).apply { open() }
        assertTrue(dao.fileStates(OutboxKind.SCAN).first().isEmpty())
        assertEquals(
            UploadState.GONE,
            ScanPanel.stateOf(reopened.scans.value!!.single(), emptyMap())
        )
    }

    @Test
    fun `a refused scan is set aside, and the purge deletes its copy`() = runTest {
        respond(422, """{"error":"bad_document","message":"Invalid document payload"}""")
        val pdf = queueScan()

        drainer().drainAll()
        assertEquals(1, dao.countDead())
        assertTrue("set aside, the copy is kept", pdf.exists())

        val later = 1_000L + OutboxLimits.DEAD_RETENTION_MILLIS + 1
        drainer(now = { later }).drainAll()

        assertEquals(0, dao.countDead())
        assertFalse(pdf.exists())
    }

    @Test
    fun `upload recovers a scan whose intake committed before its history could be saved`() =
        runTest {
            val unavailable = folder.newFolder("history.json")
            val intake = ScanIntake(folder.root, dao, ScanLog(unavailable)) { 123L }
            val scan = intake.receive(
                PDF.inputStream(),
                "original.pdf",
                "application/pdf",
                "Scanner",
                null
            )
            assertTrue(unavailable.delete())
            respond(200, """{"status":"success","document_id":8}""")

            assertTrue(drainer().drainAll().delivered)
            assertEquals(1, server.requestCount)
            val reopened = ScanLog(unavailable).apply { open() }
            assertEquals(listOf(scan.copy(outcome = UploadState.DELIVERED)), reopened.scans.value)
            assertTrue(dao.fileStates(OutboxKind.SCAN).first().isEmpty())
            assertFalse(File(scan.filePath).exists())
        }

    @Test
    fun `a late receipt for a trimmed scan preserves the ten newer scans and their previews`() =
        runTest {
            val old = queueScan()
            val newer = (1..ScanLog.KEPT).map {
                val preview = folder.newFile("preview-$it")
                Scan("path-$it", "scan-$it.pdf", "Scanner", 10L + it, preview.path)
            }
            newer.forEach { history.add(it) }
            respond(200, """{"status":"success","document_id":8}""")

            assertTrue(drainer().drainAll().delivered)
            val reopened = ScanLog(File(folder.root, "history.json")).apply { open() }
            assertEquals(newer.reversed(), reopened.scans.value)
            assertTrue(newer.all { File(requireNotNull(it.previewPath)).exists() })
            assertFalse(old.exists())
        }

    @Test
    fun `an acknowledged scan retries a failed history write without reposting`() = runTest {
        val pdf = queueScan()
        history.add(Scan(pdf.path, pdf.name, "Scanner", 1))
        respond(200, """{"status":"success","document_id":8}""")
        val broken = drainer(remember = { _, _ -> throw IOException("history unavailable") })
        assertTrue(broken.drainAll().delivered)

        val rows = dao.fileStates(OutboxKind.SCAN).first().associateBy { it.filePath }
        assertEquals(UploadState.DELIVERED, ScanPanel.stateOf(history.scans.value!!.single(), rows))
        assertEquals(0, dao.countOf(OutboxKind.SCAN))
        drainer().drainAll()
        assertEquals(1, server.requestCount)
        assertTrue(dao.fileStates(OutboxKind.SCAN).first().isEmpty())
        val reopened = ScanLog(File(folder.root, "history.json")).apply { open() }
        assertEquals(UploadState.DELIVERED, reopened.scans.value!!.single().outcome)
        assertFalse(pdf.exists())
    }

    @Test
    fun `one failed history page leaves later cleanup pages free to finish`() = runTest {
        val total = 150
        dao.insertAll(
            (0 until total).map {
                OutboxEntry(
                    kind = OutboxKind.SCAN,
                    payload = "{}",
                    filePath = File(folder.root, "$it.pdf").path,
                    createdAt = 1,
                    deadAt = 1_000,
                    deadReason = DeadReason.GONE,
                )
            }
        )
        val later = 1_001L + OutboxLimits.DEAD_RETENTION_MILLIS
        val sizes = mutableListOf<Int>()
        val report = drainer(
            remember = { _, outcomes ->
                sizes += outcomes.size
                if (sizes.size == 1) throw IOException("history busy")
            },
            now = { later },
        ).drainAll()
        assertTrue(sizes.size > 1)
        assertEquals(total, sizes.sum())
        assertEquals(total - sizes.first(), report.purged)
        assertEquals(sizes.first(), drainer(now = { later }).drainAll().purged)
        assertTrue(dao.fileStates(OutboxKind.SCAN).first().isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an upgrade reviving an expired scan during archival keeps its source`() = runTest {
        val pdf = queueScan()
        val row = dao.take(OutboxKind.SCAN, 1).single()
        respond(200, """{"status":"success","document_id":3}""")
        dao.markDeadIds(listOf(row.id), 1_000, "older format", DeadReason.UNDECODABLE)

        val report = drainer(
            remember = { _, outcomes ->
                if (outcomes.any {
                        it.filePath == pdf.path && it.outcome == UploadState.SET_ASIDE
                    }
                ) {
                    assertEquals(1, dao.reviveUndecodable())
                }
            },
            now = { 1_001L + OutboxLimits.DEAD_RETENTION_MILLIS },
        ).drainAll()

        assertEquals(0, report.purged)
        assertEquals(1, report.accepted[OutboxKind.SCAN])
        assertEquals(1, server.requestCount)
        val sent = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        val bytes = Base64.getDecoder().decode(sent.getValue("file_b64").jsonPrimitive.content)
        assertEquals(PDF.toList(), bytes.toList())
    }

    @Test
    fun `a successful envelope that explicitly skips its scan preserves the source`() = runTest {
        val pdf = queueScan()
        respond(200, """{"status":"success","accepted":0,"skipped":1,"skipped_indexes":[0]}""")
        val report = drainer().drainAll()
        assertFalse(report.delivered)
        assertEquals(1, report.skipped[OutboxKind.SCAN])
        assertTrue(pdf.exists())
        assertEquals(
            DeadReason.REFUSED,
            dao.fileStates(OutboxKind.SCAN).first().single().deadReason
        )
    }

    @Test
    fun `temporarily inaccessible scans wait without blocking a readable scan`() = runTest {
        val locked = folder.newFolder("locked")
        val file = queueScan("locked/first.pdf")
        queueScan("second.pdf")
        assertTrue(locked.setExecutable(false, false))
        try {
            assumeFalse("host user bypasses directory permissions", locked.canExecute())
            respond(200, """{"status":"success","document_id":5}""")
            val report = drainer().drainAll()
            assertEquals(1, report.accepted[OutboxKind.SCAN])
            assertEquals(0, report.lost)
            val waiting = dao.take(OutboxKind.SCAN, 5).single()
            assertEquals(file.path, waiting.filePath)
            assertEquals(0, waiting.attempts)
            assertTrue(waiting.retryAfter > 1_000L)
            assertEquals(0, dao.countDead())
        } finally {
            assertTrue(locked.setExecutable(true, true))
        }
        respond(200, """{"status":"success","document_id":6}""")
        assertTrue(drainer { 61_001L }.drainAll().delivered)
        assertEquals(2, server.requestCount)
    }

    private companion object {
        val PDF = "%PDF-1.4 a scanned page".toByteArray()
    }
}

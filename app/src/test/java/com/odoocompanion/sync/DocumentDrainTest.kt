package com.odoocompanion.sync

import android.app.Application
import android.database.sqlite.SQLiteFullException
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.odoocompanion.config.Settings
import com.odoocompanion.data.CompanionDatabase
import com.odoocompanion.data.DeadReason
import com.odoocompanion.data.FileStamp
import com.odoocompanion.data.OutboxDao
import com.odoocompanion.data.OutboxEntry
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.data.OutboxLimits
import com.odoocompanion.data.UploadState
import com.odoocompanion.net.OdooClient
import com.odoocompanion.whatsapp.Classification
import com.odoocompanion.whatsapp.DocumentKind
import com.odoocompanion.whatsapp.DocumentReader
import com.odoocompanion.whatsapp.DocumentSnapshot
import com.odoocompanion.whatsapp.Sighting
import com.odoocompanion.whatsapp.WhatsAppIntake
import com.odoocompanion.whatsapp.WhatsAppNotices
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DocumentDrainTest {
    @get:Rule
    val folder = TemporaryFolder()

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

    private fun drainer(
        reader: DocumentReader = DocumentReader(folder.root),
        client: OdooClient = OdooClient(),
        outbox: OutboxDao = dao,
        now: () -> Long = { 1_000L },
    ) = OutboxDrainer(outbox, client, now, documentReader = reader) {
        Settings(
            baseUrl = server.url("/").toString().trimEnd('/'),
            identifier = "phone-01",
            token = "t",
        )
    }

    private fun respond(code: Int, body: String) {
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
    }

    private suspend fun queueDocument(legacy: Boolean = false): File {
        val pdf = folder.newFile("CSF PCA221013LF1.pdf").apply { writeBytes(PDF) }
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.DOCUMENT,
                payload = """{"file_name":"CSF PCA221013LF1.pdf","mimetype":"application/pdf",""" +
                    """"kind":"csf","rfc":"PCA221013LF1","chat":"Yop"}""",
                filePath = pdf.absolutePath,
                createdAt = pdf.lastModified(),
                source = if (legacy) {
                    null
                } else {
                    FileStamp.read(
                        pdf
                    )!!.copy(sha256 = DocumentSnapshot.digest(pdf, pdf.length()))
                },
            ),
        )
        return pdf
    }

    @Test
    fun `a document goes to document under file_b64 with its metadata, and its file stays`() =
        runTest {
            respond(200, """{"service":"remote_mobile","status":"success","document_id":9}""")
            val pdf = queueDocument()

            ShadowLog.setLoggable("OutboxDrainer", Log.DEBUG)
            val report = drainer().drainAll()
            val logs = ShadowLog.getLogsForTag("OutboxDrainer").map { it.msg }
            assertTrue(logs.any { "drain finished" in it })
            assertFalse(logs.any { "PCA221013LF1" in it || "Yop" in it })

            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("/remote/mobile/phone-01/document", request.url.encodedPath)
            val sent = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
            assertEquals(
                PDF.toList(),
                Base64.getDecoder().decode(sent.getValue("file_b64").jsonPrimitive.content)
                    .toList(),
            )
            assertEquals("csf", sent.getValue("kind").jsonPrimitive.content)
            assertFalse("what the handset read stays on the handset", "rfc" in sent)
            assertTrue(report.delivered)
            assertEquals(0, dao.countOf(OutboxKind.DOCUMENT))
            assertTrue("WhatsApp's file is not the outbox's to delete", pdf.exists())
            // what the notice will say reached Documents
            val noticed = report.deliveredDocuments.single()
            assertEquals("CSF PCA221013LF1.pdf", noticed.metadata.fileName)
            assertEquals("Yop", noticed.metadata.chat)
            assertEquals("PCA221013LF1", noticed.rfc)
        }

    @Test
    fun `a document the server already had is a duplicate, and its file still stays`() = runTest {
        respond(
            200,
            """{"service":"remote_mobile","status":"success","document_id":9,"duplicates":1}""",
        )
        val pdf = queueDocument()

        val report = drainer().drainAll()

        assertEquals(mapOf(OutboxKind.DOCUMENT to 1), report.duplicates)
        assertTrue(pdf.exists())
        assertTrue(
            "Odoo already had it: its notice went the first time",
            report.deliveredDocuments.isEmpty()
        )
    }

    @Test
    fun `an upload streams the verified copy even if another writer replaces the original`() =
        runTest {
            val pdf = queueDocument()
            val modified = pdf.lastModified()
            val replacement = ByteArray(PDF.size) { 42 }
            val http = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                pdf.writeBytes(replacement)
                assertTrue(pdf.setLastModified(modified))
                chain.proceed(chain.request())
            }.build()
            respond(200, """{"status":"success","document_id":1}""")

            assertTrue(drainer(client = OdooClient(http)).drainAll().delivered)
            assertEquals(1, server.requestCount)
            val sent = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            val bytes = Base64.getDecoder().decode(sent.getValue("file_b64").jsonPrimitive.content)
            assertEquals(PDF.toList(), bytes.toList())
            assertEquals(replacement.toList(), pdf.readBytes().toList())
        }

    @Test
    fun `an unreadable legacy document stays queued then matching OCR permits delivery`() =
        runTest {
            val pdf = queueDocument(legacy = true)
            var verdict: Classification? = null
            val reader = DocumentReader(folder.root) { verdict }
            respond(200, """{"status":"success","document_id":2}""")

            assertFalse(drainer(reader).drainAll().delivered)
            assertEquals(0, server.requestCount)
            val held = dao.take(OutboxKind.DOCUMENT, 1).single()
            assertEquals(0, held.attempts)
            assertTrue(held.retryAfter > 1_000L)
            verdict = Classification(DocumentKind.CSF, "PCA221013LF1")
            assertTrue(drainer(reader, now = { held.retryAfter }).drainAll().delivered)
            assertEquals(1, server.requestCount)
            assertTrue(pdf.exists())
        }

    @Test
    fun `a retry of a reclassified legacy row stays bound to the first verified bytes`() = runTest {
        val pdf = queueDocument(legacy = true)
        val modified = pdf.lastModified()
        var reads = 0
        val reader = DocumentReader(folder.root) {
            reads++
            Classification(DocumentKind.CSF, "PCA221013LF1")
        }
        respond(503, """{"service":"remote_mobile","error":"unavailable"}""")
        assertFalse(drainer(reader).drainAll().delivered)
        assertEquals(1, server.requestCount)
        val row = dao.take(OutboxKind.DOCUMENT, 1).single()
        pdf.writeBytes(ByteArray(PDF.size) { 43 })
        assertTrue(pdf.setLastModified(modified))
        respond(200, """{"status":"success","document_id":3}""")

        assertEquals(1, drainer(reader, now = { maxOf(2_000L, row.retryAfter) }).drainAll().lost)
        assertEquals(1, reads)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a failed digest write keeps the legacy upload pending without charging a request`() =
        runTest {
            queueDocument(legacy = true)
            val full = object : OutboxDao by dao {
                override suspend fun pinRecheckedDocument(id: Long, sha256: String): Int =
                    throw SQLiteFullException()
            }
            val reader =
                DocumentReader(folder.root) { Classification(DocumentKind.CSF, "PCA221013LF1") }
            val report = drainer(reader, outbox = full).drainAll()
            assertEquals(OutboxDrainer.Outcome.RETRY, report.outcome)
            assertEquals(0, dao.take(OutboxKind.DOCUMENT, 1).single().attempts)
            assertEquals(0, server.requestCount)
            respond(200, """{"status":"success","document_id":4}""")
            assertTrue(drainer(reader).drainAll().delivered)
        }

    @Test
    fun `a legacy verdict for a different tax identifier never authorizes an upload`() = runTest {
        queueDocument(legacy = true)
        val reader = DocumentReader(folder.root) { Classification(DocumentKind.CSF, "OTHER") }
        val report = drainer(reader).drainAll()
        assertEquals(1, report.lost)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a legacy document replaced after enqueue is held without sending its replacement`() =
        runTest {
            val pdf = queueDocument(legacy = true)
            assertTrue(pdf.setLastModified(pdf.lastModified() + 60_000))
            respond(200, """{"status":"success"}""")

            assertEquals(1, drainer().drainAll().lost)
            assertEquals(0, server.requestCount)
            assertTrue(pdf.exists())
        }

    @Test
    fun `a refused document is set aside, and purging it never deletes WhatsApp's file`() =
        runTest {
            respond(422, """{"error":"bad_document","message":"Invalid document payload"}""")
            val pdf = queueDocument()

            val report = drainer().drainAll()
            assertEquals(1, dao.countDead())
            assertTrue(report.deliveredDocuments.isEmpty())

            val later = 1_000L + OutboxLimits.DEAD_RETENTION_MILLIS + 1
            drainer(now = { later }).drainAll()

            assertEquals(0, dao.countDead())
            assertTrue("a purge removes the row, not the customer's document", pdf.exists())
        }

    private companion object {
        val PDF = "%PDF-1.4 a constancia".toByteArray()
    }

    @Test
    fun `a document whose file is gone is never shown as delivered`() = runTest {
        val pdf = queueDocument()
        val source = dao.take(OutboxKind.DOCUMENT, 1).single().source
        pdf.delete()

        val report = drainer().drainAll()

        assertEquals(0, server.requestCount)
        assertEquals(1, report.lost)
        val queue = dao.fileStates(OutboxKind.DOCUMENT).first().groupBy { it.filePath }
        assertEquals(DeadReason.GONE, queue.getValue(pdf.absolutePath).single().deadReason)
        assertEquals("nothing an operator can fix", 0, dao.counts(null).first().undeliverable)
        val sighting = Sighting(
            app = WhatsAppNotices.WHATSAPP,
            chat = "Yop",
            sender = "Yop",
            label = pdf.name,
            at = 1L,
            filePath = pdf.absolutePath,
            classification = Classification(DocumentKind.CSF, source = source),
            queued = true,
        )
        assertEquals(UploadState.GONE, WhatsAppIntake.uploadStateOf(sighting, queue))
    }
}

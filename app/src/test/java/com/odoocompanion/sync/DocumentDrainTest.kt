package com.odoocompanion.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.odoocompanion.config.Settings
import com.odoocompanion.data.CompanionDatabase
import com.odoocompanion.data.OutboxDao
import com.odoocompanion.data.OutboxEntry
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.data.OutboxLimits
import com.odoocompanion.net.OdooClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

// A document rides the recording's path -- one streamed file per request --
// with the one difference that its file is WhatsApp's: it is never deleted,
// not on delivery and not when a set-aside row is purged.
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

    private fun drainer(now: () -> Long = { 1_000L }) = OutboxDrainer(dao, OdooClient(), now) {
        Settings(
            baseUrl = server.url("/").toString().trimEnd('/'),
            identifier = "phone-01",
            token = "t",
        )
    }

    private fun respond(code: Int, body: String) {
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
    }

    private suspend fun queueDocument(): File {
        val pdf = folder.newFile("CSF PCA221013LF1.pdf").apply { writeBytes(PDF) }
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.DOCUMENT,
                payload = """{"file_name":"CSF PCA221013LF1.pdf","mimetype":"application/pdf",""" +
                    """"kind":"csf","rfc":"PCA221013LF1","chat":"Yop"}""",
                filePath = pdf.absolutePath,
                createdAt = 1L,
            ),
        )
        return pdf
    }

    @Test
    fun `a document goes to document under file_b64 with its metadata, and its file stays`() =
        runTest {
            respond(200, """{"service":"remote_mobile","status":"success","document_id":9}""")
            val pdf = queueDocument()

            val report = drainer().drainAll()

            val request = server.takeRequest()
            assertEquals("/remote/mobile/phone-01/document", request.url.encodedPath)
            val sent = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
            assertEquals(
                PDF.toList(),
                Base64.getDecoder().decode(sent.getValue("file_b64").jsonPrimitive.content)
                    .toList(),
            )
            assertEquals("csf", sent.getValue("kind").jsonPrimitive.content)
            assertEquals("PCA221013LF1", sent.getValue("rfc").jsonPrimitive.content)
            assertTrue(report.delivered)
            assertEquals(0, dao.countOf(OutboxKind.DOCUMENT))
            assertTrue("WhatsApp's file is not the outbox's to delete", pdf.exists())
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
    }

    @Test
    fun `a refused document is set aside, and purging it never deletes WhatsApp's file`() =
        runTest {
            respond(422, """{"error":"bad_document","message":"Invalid document payload"}""")
            val pdf = queueDocument()

            drainer().drainAll()
            assertEquals(1, dao.countDead())

            val later = 1_000L + OutboxLimits.DEAD_RETENTION_MILLIS + 1
            drainer(now = { later }).drainAll()

            assertEquals(0, dao.countDead())
            assertTrue("a purge removes the row, not the customer's document", pdf.exists())
        }

    private companion object {
        val PDF = "%PDF-1.4 a constancia".toByteArray()
    }
}

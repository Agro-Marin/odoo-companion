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
import org.junit.Assert.assertFalse
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

// A scan rides the WhatsApp document's route, but its file is the app's own
// copy: delivered, the copy goes with the row, and a set-aside one is purged
// with its row after the retention period.
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

    private suspend fun queueScan(): File {
        val pdf = folder.newFile("scan.pdf").apply { writeBytes(PDF) }
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

        val report = drainer().drainAll()

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
        // the person scanned it on this phone: no notice tells them it went
        assertTrue(report.deliveredDocuments.isEmpty())
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

    private companion object {
        val PDF = "%PDF-1.4 a scanned page".toByteArray()
    }
}

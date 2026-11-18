package com.odoocompanion.scan

import android.app.Application
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.odoocompanion.data.CompanionDatabase
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.data.OutboxLimits
import com.odoocompanion.net.DocumentMetadata
import com.odoocompanion.net.WireJson
import kotlinx.coroutines.test.runTest
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
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ScanIntakeTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: CompanionDatabase

    @Before
    fun setUp() {
        ShadowLog.setLoggable("ScanIntake", Log.DEBUG)
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CompanionDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    private fun log() = ScanLog(File(folder.root, "scans.json"))

    private fun intake(log: ScanLog) =
        ScanIntake(File(folder.root, ScanIntake.FOLDER), database.outbox(), log, now = { 1_000L })

    @Test
    fun `a scan is copied into the app's folder and queued for the document route`() = runTest {
        val log = log()
        val intake = intake(log)
        val scan = intake.receive(
            byteArrayOf(1, 2, 3).inputStream(),
            "Escaneo.pdf",
            "application/pdf",
            ScanIntake.SOURCE_SCAN,
            enrollment = null,
        )
        val file = File(scan.filePath)

        assertEquals(ScanIntake.FOLDER, file.parentFile!!.name)
        assertEquals(listOf<Byte>(1, 2, 3), file.readBytes().toList())
        val row = database.outbox().take(OutboxKind.SCAN, 10).single()
        assertEquals(file.absolutePath, row.filePath)
        val metadata = WireJson.decodeFromString<DocumentMetadata>(row.payload)
        assertEquals("Escaneo.pdf", metadata.fileName)
        assertEquals("application/pdf", metadata.mimetype)
        assertEquals(ScanIntake.SOURCE_SCAN, metadata.source)
        assertEquals(1_000L, metadata.receivedAt)
        assertEquals(0, database.outbox().countOf(OutboxKind.DOCUMENT))
        assertEquals("Escaneo.pdf", log.scans.value!!.single().fileName)
    }

    @Test
    fun `a scan belongs to the enrolment it was queued under`() = runTest {
        val intake = intake(log())
        intake.receive(
            byteArrayOf(1).inputStream(),
            "Escaneo.pdf",
            "application/pdf",
            ScanIntake.SOURCE_SCAN,
            "A",
        )

        val outbox = database.outbox()
        assertEquals("A", outbox.take(OutboxKind.SCAN, 10, enrollment = "A").single().enrollment)
        assertEquals(0, outbox.take(OutboxKind.SCAN, 10, enrollment = "B").size)
    }

    @Test
    fun `the list keeps the newest scans first, and survives a restart`() = runTest {
        val intake = intake(log())
        repeat(ScanLog.KEPT + 2) { n ->
            intake.receive(
                byteArrayOf(n.toByte()).inputStream(),
                "doc-$n.pdf",
                "application/pdf",
                ScanIntake.SOURCE_FILE,
                enrollment = null,
            )
        }

        val reopened = log().apply { open() }

        val names = reopened.scans.value!!.map { it.fileName }
        assertEquals(ScanLog.KEPT, names.size)
        assertEquals("doc-${ScanLog.KEPT + 1}.pdf", names.first())
        assertTrue("the oldest fell off", "doc-0.pdf" !in names)
    }

    private suspend fun receive(input: InputStream, history: ScanLog = log()) =
        intake(history).receive(input, "doc.pdf", ScanIntake.PDF, ScanIntake.SOURCE_FILE, null)

    @Test
    fun `an empty document is rejected before queueing`() = runTest {
        assertTrue(
            runCatching {
                receive(byteArrayOf().inputStream())
            }.exceptionOrNull() is IOException
        )
        assertEquals(0, database.outbox().countOf(OutboxKind.SCAN))
        assertTrue(File(folder.root, ScanIntake.FOLDER).listFiles()!!.isEmpty())
    }

    @Test
    fun `a failed copy removes partial bytes and queues nothing`() = runTest {
        val input = object : InputStream() {
            var first = true
            override fun read() = throw UnsupportedOperationException()
            override fun read(buffer: ByteArray, offset: Int, len: Int): Int {
                if (!first) throw IOException("provider disconnected")
                first = false
                return 1
            }
        }

        assertTrue(runCatching { receive(input) }.exceptionOrNull() is IOException)
        assertTrue(File(folder.root, ScanIntake.FOLDER).listFiles()!!.isEmpty())
        assertEquals(0, database.outbox().countOf(OutboxKind.SCAN))
    }

    @Test
    fun `a provider close failure cannot leave a committed scan`() = runTest {
        val input = object : ByteArrayInputStream(byteArrayOf(42)) {
            override fun close() = throw IOException("provider close failed")
        }
        assertTrue(runCatching { receive(input) }.exceptionOrNull() is IOException)
        assertEquals(0, database.outbox().countOf(OutboxKind.SCAN))
        assertTrue(File(folder.root, ScanIntake.FOLDER).listFiles()!!.isEmpty())
    }

    @Test
    fun `a document exactly at the upload limit is accepted`() = runTest {
        val source = folder.newFile()
        RandomAccessFile(source, "rw").use { it.setLength(OutboxLimits.MAX_FILE_BYTES) }
        val scan = receive(source.inputStream())
        assertEquals(OutboxLimits.MAX_FILE_BYTES, File(scan.filePath).length())
    }

    @Test
    fun `files over the upload limit leave no private copy`() = runTest {
        val source = folder.newFile()
        RandomAccessFile(source, "rw").use { it.setLength(OutboxLimits.MAX_FILE_BYTES + 1) }
        val failure = runCatching { receive(source.inputStream()) }.exceptionOrNull()

        assertTrue(failure is DocumentTooLarge)
        assertTrue(File(folder.root, ScanIntake.FOLDER).listFiles()!!.isEmpty())
        assertEquals(0, database.outbox().countOf(OutboxKind.SCAN))
    }

    @Test
    fun `a history write failure preserves the queued original`() = runTest {
        val history = ScanLog(File(folder.newFile(), "history.json"))
        val picture = folder.newFile()
        val scan = intake(history).receive(
            byteArrayOf(42).inputStream(),
            "doc.pdf",
            ScanIntake.PDF,
            ScanIntake.SOURCE_FILE,
            null,
        ) { _, _ -> picture }
        assertTrue(scan.previewPath == null)
        assertTrue(!picture.exists())

        val row = database.outbox().take(OutboxKind.SCAN, 1).single()
        assertEquals(scan.filePath, row.filePath)
        assertEquals(listOf<Byte>(42), File(scan.filePath).readBytes().toList())
    }

    @Test
    fun `an unreadable list starts empty instead of failing`() = runTest {
        File(folder.root, "scans.json").writeText("not json")

        val log = log().apply { open() }

        assertEquals(emptyList<Scan>(), log.scans.value)
    }

    @Test
    fun `a stored copy keeps the shared name's extension`() {
        assertEquals("docx", ScanIntake.extensionFor("application/octet-stream", "Contrato.DOCX"))
        assertEquals("pdf", ScanIntake.extensionFor(ScanIntake.PDF, "Escaneo 2026-11-17 10.30.pdf"))
    }

    @Test
    fun `without a usable name the extension comes from the type`() {
        assertEquals("jpg", ScanIntake.extensionFor("image/jpeg", "Documento"))
        assertEquals("png", ScanIntake.extensionFor("image/png", null))
        assertEquals("bin", ScanIntake.extensionFor("application/zip", "raro.a b"))
    }
}

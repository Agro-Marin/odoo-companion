package com.odoocompanion.scan

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.odoocompanion.data.CompanionDatabase
import com.odoocompanion.data.OutboxKind
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ScanIntakeTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: CompanionDatabase

    @Before
    fun setUp() {
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
        val file = intake.copy(byteArrayOf(1, 2, 3).inputStream(), "pdf")

        intake.queue(file, "Escaneo.pdf", "application/pdf", ScanIntake.SOURCE_SCAN)

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
    fun `the list keeps the newest scans first, and survives a restart`() = runTest {
        val intake = intake(log())
        repeat(ScanLog.KEPT + 2) { n ->
            val file = intake.copy(byteArrayOf(n.toByte()).inputStream(), "pdf")
            intake.queue(file, "doc-$n.pdf", "application/pdf", ScanIntake.SOURCE_FILE)
        }

        val reopened = log().apply { open() }

        val names = reopened.scans.value!!.map { it.fileName }
        assertEquals(ScanLog.KEPT, names.size)
        assertEquals("doc-${ScanLog.KEPT + 1}.pdf", names.first())
        assertTrue("the oldest fell off", "doc-0.pdf" !in names)
    }

    @Test
    fun `an unreadable list starts empty instead of failing`() = runTest {
        File(folder.root, "scans.json").writeText("not json")

        val log = log().apply { open() }

        assertEquals(emptyList<Scan>(), log.scans.value)
    }
}

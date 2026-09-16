package com.odoocompanion.recording

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.odoocompanion.data.CompanionDatabase
import com.odoocompanion.data.DeadReason
import com.odoocompanion.data.OutboxDao
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.net.RecordingMetadata
import com.odoocompanion.net.WireJson
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RecordingHarvestTest {
    private lateinit var database: CompanionDatabase
    private lateinit var dao: OutboxDao
    private lateinit var root: File
    private var clock = 10_000_000L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CompanionDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.outbox()
        root = File.createTempFile("harvest", "").apply {
            delete()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    private fun recording(name: String, modifiedAt: Long = clock - 120_000): File {
        val dir = File(root, "Recordings/Call").apply { mkdirs() }
        return File(dir, name).apply {
            writeBytes(ByteArray(64))
            setLastModified(modifiedAt)
        }
    }

    private fun harvest() = RecordingHarvest(dao, root) { clock }

    @Test
    fun `a settled recording is queued with the metadata the endpoint reads`() = runTest {
        recording("call_5512345678_20260728.m4a")

        val batch = harvest().queueNew()

        assertEquals(1, batch.queued)
        val entry = dao.take(OutboxKind.RECORDING, 10).single()
        val metadata = WireJson.decodeFromString<RecordingMetadata>(entry.payload)
        assertEquals("5512345678", metadata.number)
        assertEquals("audio/mp4", metadata.mimetype)
        assertEquals("call_5512345678_20260728.m4a", metadata.fileName)
    }

    @Test
    fun `a file still being written is left for the next pass`() = runTest {
        recording("fresh.m4a", modifiedAt = clock - 1_000)

        val batch = harvest().queueNew()

        assertEquals(0, batch.queued)
        assertEquals(0, dao.countOf(OutboxKind.RECORDING))
    }

    @Test
    fun `a recording already queued is not queued again`() = runTest {
        recording("a.m4a")

        val first = harvest().queueNew()
        val second = harvest().queueNew()

        assertEquals(1, first.queued)
        assertEquals(0, second.queued)
        assertEquals(1, dao.countOf(OutboxKind.RECORDING))
    }

    @Test
    fun `nothing on disk queues nothing`() = runTest {
        assertEquals(0, harvest().queueNew().queued)
    }

    @Test
    fun `every settled recording on disk is queued, however old`() = runTest {
        recording("old.m4a", modifiedAt = clock - 500_000)
        recording("new.m4a", modifiedAt = clock - 100_000)

        assertEquals(2, harvest().queueNew().queued)
    }

    // Proven on the previous tree: the second file was never queued, because
    // its write time fell below where the cursor had already reached.
    @Test
    fun `a recording stamped before an earlier harvest is still queued`() = runTest {
        recording("first.m4a", modifiedAt = clock - 120_000)
        harvest().queueNew()

        // The phone's clock is corrected an hour backwards before the next call.
        clock -= 3_600_000
        recording("second.m4a", modifiedAt = clock - 120_000)
        clock += 7_200_000

        assertEquals(1, harvest().queueNew().queued)
        assertEquals(2, dao.countOf(OutboxKind.RECORDING))
    }

    // Delivered, file left behind because it would not delete: the row is kept
    // dead precisely so that this pass does not treat the file as new.
    @Test
    fun `a recording delivered but left on disk is not queued again`() = runTest {
        recording("a.m4a")
        harvest().queueNew()
        val id = dao.take(OutboxKind.RECORDING, 1).single().id
        dao.markDeadIds(
            listOf(id),
            now = clock,
            reason = "uploaded",
            deadReason = DeadReason.KEPT_ON_DISK
        )

        assertEquals(0, harvest().queueNew().queued)
    }

    @Test
    fun `a recording set aside as undeliverable is not harvested again`() = runTest {
        recording("a.m4a")
        harvest().queueNew()
        val id = dao.take(OutboxKind.RECORDING, 1).single().id
        dao.markDeadIds(listOf(id), now = clock, reason = "refused")

        val second = harvest().queueNew()

        assertEquals(0, second.queued)
        assertEquals(1, dao.countDead())
    }

    @Test
    fun `extensions the dialer does not write are ignored`() = runTest {
        File(root, "Recordings/Call").apply { mkdirs() }
        File(root, "Recordings/Call/notes.txt").apply {
            writeBytes(ByteArray(8))
            setLastModified(clock - 120_000)
        }

        assertEquals(0, harvest().queueNew().queued)
    }

    @Test
    fun `every audio extension the scanner accepts gets a real mime type`() {
        val unmapped = RecordingScanner.AUDIO_EXTENSIONS.filter { extension ->
            RecordingHarvest.mimeTypeOf(File("x.$extension")) == "application/octet-stream"
        }

        assertTrue("unmapped: $unmapped", unmapped.isEmpty())
    }
}

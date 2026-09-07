package com.odoocompanion.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OutboxTest {
    private lateinit var database: CompanionDatabase
    private lateinit var dao: OutboxDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CompanionDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.outbox()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun addLocations(count: Int, startedAt: Long = 1_000L) {
        dao.insertAll(
            (0 until count).map { i ->
                OutboxEntry(
                    kind = OutboxKind.LOCATION,
                    payload = """{"latitude":1.0,"longitude":2.0,"seq":$i}""",
                    createdAt = startedAt + i,
                )
            },
        )
    }

    private suspend fun addCalls(count: Int, startedAt: Long = 2_000L) {
        dao.insertAll(
            (0 until count).map { i ->
                OutboxEntry(
                    kind = OutboxKind.CALL_LOG,
                    payload = """{"number":"+1","direction":"incoming",""" +
                        """"timestamp":$i,"duration":1}""",
                    createdAt = startedAt + i,
                )
            },
        )
    }

    @Test
    fun `trimming keeps the newest fixes and drops the oldest`() = runTest {
        addLocations(10)

        dao.trimOldest(OutboxKind.LOCATION, keep = 4)

        val remaining = dao.take(OutboxKind.LOCATION, 100)
        assertEquals(4, remaining.size)
        assertEquals(
            listOf(1006L, 1007L, 1008L, 1009L),
            remaining.map { it.createdAt }.sorted(),
        )
    }

    @Test
    fun `trimming below the cap changes nothing`() = runTest {
        addLocations(3)

        dao.trimOldest(OutboxKind.LOCATION, keep = 50)

        assertEquals(3, dao.countOf(OutboxKind.LOCATION))
    }

    @Test
    fun `trimming location never discards calls or recordings`() = runTest {
        addLocations(5)
        dao.insert(
            OutboxEntry(kind = OutboxKind.CALL_LOG, payload = "{}", createdAt = 1L),
        )
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.RECORDING,
                payload = "{}",
                filePath = "/sdcard/x.m4a",
                createdAt = 2L,
            ),
        )

        dao.trimOldest(OutboxKind.LOCATION, keep = 1)

        assertEquals(1, dao.countOf(OutboxKind.LOCATION))
        assertEquals(1, dao.countOf(OutboxKind.CALL_LOG))
        assertEquals(1, dao.countOf(OutboxKind.RECORDING))
    }

    @Test
    fun `exhausted rows stop being taken but are kept and counted`() = runTest {
        addCalls(2)
        val entries = dao.take(OutboxKind.CALL_LOG, 100)
        repeat(25) { dao.markFailed(listOf(entries.first().id), "boom") }

        assertEquals(
            1,
            dao.markDead(OutboxKind.IRREPLACEABLE, maxAttempts = 25, now = 1_000L),
        )

        assertEquals(1, dao.countOf(OutboxKind.CALL_LOG))
        assertEquals(1, dao.countDead())
        assertEquals(2, dao.countIncludingDead(OutboxKind.CALL_LOG))
        assertEquals(1, dao.take(OutboxKind.CALL_LOG, 100).size)
    }

    @Test
    fun `an exhausted position is discarded rather than dead-lettered`() = runTest {
        addLocations(2)
        addCalls(1)
        val fix = dao.take(OutboxKind.LOCATION, 100).first()
        val call = dao.take(OutboxKind.CALL_LOG, 100).first()
        repeat(25) { dao.markFailed(listOf(fix.id, call.id), "boom") }

        assertEquals(1, dao.markDead(OutboxKind.IRREPLACEABLE, 25, 1_000L))
        assertEquals(1, dao.deleteExhausted(OutboxKind.IRREPLACEABLE, 25))

        assertEquals(1, dao.countOf(OutboxKind.LOCATION))
        assertEquals(1, dao.countIncludingDead(OutboxKind.LOCATION))
        assertEquals(1, dao.countDead())
    }

    @Test
    fun `a row that only ran out of retries is put back with one attempt`() = runTest {
        addCalls(1)
        val id = dao.take(OutboxKind.CALL_LOG, 1).first().id
        repeat(25) { dao.markFailed(listOf(id), "auth rejected (401)") }
        dao.markDead(OutboxKind.IRREPLACEABLE, 25, 1_000L)

        assertEquals(1, dao.reviveExhausted(attempts = 24))

        assertEquals(0, dao.countDead())
        assertEquals(1, dao.countOf(OutboxKind.CALL_LOG))
        val revived = dao.take(OutboxKind.CALL_LOG, 1).first()
        assertEquals("a revival is a probe, not a fresh budget", 24, revived.attempts)
        assertEquals("the revival is counted so it can be bounded", 1, revived.revivals)
    }

    @Test
    fun `a row that keeps failing after its revivals is refused, not revived again`() = runTest {
        addCalls(1)
        val id = dao.take(OutboxKind.CALL_LOG, 1).first().id
        repeat(3) {
            repeat(25) { dao.markFailed(listOf(id), "server 500") }
            dao.markDead(OutboxKind.IRREPLACEABLE, 25, 1_000L)
            dao.reviveExhausted(attempts = 24)
        }
        repeat(25) { dao.markFailed(listOf(id), "server 500") }

        assertEquals(1, dao.markUnreachable(OutboxKind.IRREPLACEABLE, 25, 3, 2_000L))

        assertEquals(1, dao.countDead())
        assertEquals("a refused row is never put back", 0, dao.reviveExhausted(attempts = 24))
        assertEquals(1, dao.countDead())
    }

    @Test
    fun `a refused row is not put back`() = runTest {
        addCalls(1)
        val id = dao.take(OutboxKind.CALL_LOG, 1).first().id
        dao.markDeadIds(listOf(id), 1_000L, "server refused the payload (400)")

        assertEquals(0, dao.reviveExhausted(attempts = 24))

        assertEquals(1, dao.countDead())
    }

    @Test
    fun `trimming cuts back below the cap so it need not run again next fix`() = runTest {
        addLocations(OutboxLimits.MAX_QUEUED_FIXES + 1)

        val removed = dao.trimOldest(OutboxKind.LOCATION, OutboxLimits.TRIMMED_FIXES)

        assertEquals(
            OutboxLimits.MAX_QUEUED_FIXES + 1 - OutboxLimits.TRIMMED_FIXES,
            removed,
        )
        assertEquals(OutboxLimits.TRIMMED_FIXES, dao.countOf(OutboxKind.LOCATION))

        assertTrue(
            "no headroom left",
            OutboxLimits.MAX_QUEUED_FIXES - OutboxLimits.TRIMMED_FIXES >= 1_000,
        )
    }

    @Test
    fun `dead rows are returned for purging once they are old enough`() = runTest {
        addLocations(1)
        val id = dao.take(OutboxKind.LOCATION, 1).first().id
        dao.markDeadIds(listOf(id), now = 1_000L, reason = "gone")

        assertEquals(0, dao.deleteDeadBefore(500L))
        assertEquals(1, dao.deleteDeadBefore(2_000L))
    }

    @Test
    fun `a purge larger than SQLite's parameter limit binds no parameter per row`() = runTest {
        addCalls(2_000)
        dao.markFailed(dao.take(OutboxKind.CALL_LOG, 2_000).map { it.id }, "x")
        assertEquals(2_000, dao.markDead(OutboxKind.IRREPLACEABLE, 1, now = 1_000L))

        assertEquals(2_000, dao.deleteDeadBefore(2_000L))
        assertEquals(0, dao.countIncludingDead(OutboxKind.CALL_LOG))
    }

    @Test
    fun `only a dead row's audio path is read for the purge`() = runTest {
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.RECORDING,
                payload = "{}",
                filePath = "/sdcard/Recordings/Call/a.m4a",
                createdAt = 1L,
            ),
        )
        addCalls(1)
        val ids = dao.take(OutboxKind.RECORDING, 1).map { it.id } +
            dao.take(OutboxKind.CALL_LOG, 1).map { it.id }
        dao.markDeadIds(ids, now = 1_000L, reason = "gone")

        assertEquals(listOf("/sdcard/Recordings/Call/a.m4a"), dao.deadFilesBefore(2_000L))
    }

    @Test
    fun `a recording is found by its file path so it is queued only once`() = runTest {
        val path = "/sdcard/Recordings/Call/a.m4a"
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.RECORDING,
                payload = "{}",
                filePath = path,
                createdAt = 1L,
            ),
        )

        assertEquals(listOf(path), dao.filesQueued(OutboxKind.RECORDING))
        assertEquals(emptyList<String>(), dao.filesQueued(OutboxKind.CALL_LOG))
    }
}

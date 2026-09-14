package com.odoocompanion.location

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.odoocompanion.data.CompanionDatabase
import com.odoocompanion.data.OutboxDao
import com.odoocompanion.data.OutboxEntry
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.data.OutboxLimits
import com.odoocompanion.data.QueueDepth
import com.odoocompanion.net.LocationFix
import com.odoocompanion.sync.UploadCadence
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LocationQueueTest {
    private lateinit var database: CompanionDatabase
    private lateinit var dao: OutboxDao
    private var clock = 1_000_000L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CompanionDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.outbox()
    }

    @After
    fun tearDown() = database.close()

    private fun queue() = LocationQueue(dao) { clock }

    private fun fix(seq: Int = 0) = LocationFix(
        latitude = 19.4326 + seq / 100_000.0,
        longitude = -99.1332,
        timestamp = clock,
    )

    private suspend fun fill(count: Int) {
        (0 until count).chunked(2_000).forEach { chunk ->
            dao.insertAll(
                chunk.map {
                    OutboxEntry(
                        kind = OutboxKind.LOCATION,
                        payload = """{"latitude":1.0,"longitude":2.0,"timestamp":$it}""",
                        createdAt = it.toLong(),
                    )
                },
            )
        }
    }

    // A fix 1.11 m north of the last one: what a phone standing still produces.
    private fun nudged(metres: Double) = LocationFix(
        latitude = 19.4326 + metres / 111_320.0,
        longitude = -99.1332,
        timestamp = clock,
    )

    @Test
    fun `with no threshold every fix is kept, exactly as before`() = runTest {
        val queue = queue()

        queue.record(listOf(nudged(0.0)), windowSeconds = 0)
        queue.record(listOf(nudged(1.0)), windowSeconds = 0)

        assertEquals(2, dao.countOf(OutboxKind.LOCATION))
    }

    @Test
    fun `a phone standing still stops paying for a row a minute`() = runTest {
        val queue = queue()

        queue.record(listOf(nudged(0.0)), windowSeconds = 0, minMoveMetres = 25)
        repeat(5) { queue.record(listOf(nudged(2.0 + it)), windowSeconds = 0, minMoveMetres = 25) }

        assertEquals(1, dao.countOf(OutboxKind.LOCATION))
    }

    @Test
    fun `a phone that moved past the threshold is kept`() = runTest {
        val queue = queue()

        queue.record(listOf(nudged(0.0)), windowSeconds = 0, minMoveMetres = 25)
        queue.record(listOf(nudged(40.0)), windowSeconds = 0, minMoveMetres = 25)

        assertEquals(2, dao.countOf(OutboxKind.LOCATION))
    }

    @Test
    fun `movement is measured from the last fix kept, not the last one seen`() = runTest {
        val queue = queue()

        queue.record(listOf(nudged(0.0)), windowSeconds = 0, minMoveMetres = 25)
        // Three drifts of 10 m each: none alone clears the threshold, but they
        // add up to 30 m from where the phone was last recorded.
        queue.record(listOf(nudged(10.0)), windowSeconds = 0, minMoveMetres = 25)
        queue.record(listOf(nudged(20.0)), windowSeconds = 0, minMoveMetres = 25)
        queue.record(listOf(nudged(30.0)), windowSeconds = 0, minMoveMetres = 25)

        assertEquals(2, dao.countOf(OutboxKind.LOCATION))
    }

    @Test
    fun `a still phone is heard from on the heartbeat, so it is not a dead one`() = runTest {
        val queue = queue()
        queue.record(listOf(nudged(0.0)), windowSeconds = 0, minMoveMetres = 25)

        clock += LocationQueue.HEARTBEAT_MILLIS
        queue.record(listOf(nudged(1.0)), windowSeconds = 0, minMoveMetres = 25)

        assertEquals(2, dao.countOf(OutboxKind.LOCATION))
    }

    @Test
    fun `a batch whose every fix is filtered asks for no upload`() = runTest {
        val queue = queue()
        queue.record(listOf(nudged(0.0)), windowSeconds = 0, minMoveMetres = 25)

        assertFalse(queue.record(listOf(nudged(1.0)), windowSeconds = 0, minMoveMetres = 25))
    }

    @Test
    fun `the distance is real metres, not degrees`() {
        // One degree of latitude is about 111 km anywhere on Earth.
        val metres = LocationQueue.metresBetween(
            LocationFix(latitude = 19.0, longitude = -99.0, timestamp = 0),
            LocationFix(latitude = 20.0, longitude = -99.0, timestamp = 0),
        )

        assertTrue("was $metres", metres in 110_000.0..112_000.0)
    }

    @Test
    fun `a fix is queued as the payload the endpoint reads`() = runTest {
        queue().record(listOf(fix()), windowSeconds = 180)

        val stored = dao.take(OutboxKind.LOCATION, 10).single()
        assertEquals(OutboxKind.LOCATION, stored.kind)
        assertTrue(stored.payload, """"latitude":19.4326""" in stored.payload)
        assertEquals(clock, stored.createdAt)
    }

    @Test
    fun `an empty result queues nothing and asks for nothing`() = runTest {
        assertFalse(queue().record(emptyList(), windowSeconds = 180))
        assertEquals(0, dao.countOf(OutboxKind.LOCATION))
    }

    @Test
    fun `a fresh fix waits and an aged queue goes`() = runTest {
        assertFalse(queue().record(listOf(fix(1)), windowSeconds = 180))

        clock += 180_000

        assertTrue(queue().record(listOf(fix(2)), windowSeconds = 180))
    }

    @Test
    fun `a window of zero sends every fix, exactly as before`() = runTest {
        assertTrue(queue().record(listOf(fix()), windowSeconds = 0))
    }

    @Test
    fun `enough waiting fixes go before the window elapses`() = runTest {
        repeat(UploadCadence.BATCH_THRESHOLD - 1) {
            assertFalse(queue().record(listOf(fix(it)), windowSeconds = 3_600))
        }

        assertTrue(queue().record(listOf(fix(99)), windowSeconds = 3_600))
    }

    @Test
    fun `at the cap the queue is cut back far enough to stay under it`() = runTest {
        fill(OutboxLimits.MAX_QUEUED_FIXES)

        queue().record(listOf(fix()), windowSeconds = 180)

        assertEquals(OutboxLimits.TRIMMED_FIXES, dao.countOf(OutboxKind.LOCATION))
    }

    @Test
    fun `the next thousand fixes past the cap do not each pay for a trim`() = runTest {
        fill(OutboxLimits.MAX_QUEUED_FIXES)
        val queue = queue()
        queue.record(listOf(fix()), windowSeconds = 180)
        val afterFirstTrim = dao.countOf(OutboxKind.LOCATION)

        repeat(50) { queue.record(listOf(fix(it)), windowSeconds = 180) }

        assertEquals(afterFirstTrim + 50, dao.countOf(OutboxKind.LOCATION))
        assertTrue(dao.countOf(OutboxKind.LOCATION) < OutboxLimits.MAX_QUEUED_FIXES)
    }

    @Test
    fun `trimming keeps the newest fixes`() = runTest {
        fill(OutboxLimits.MAX_QUEUED_FIXES)

        queue().record(listOf(fix()), windowSeconds = 180)

        val oldest = dao.oldestCreatedAt(OutboxKind.LOCATION)!!
        assertTrue(
            "kept the wrong end of the queue",
            oldest >= (OutboxLimits.MAX_QUEUED_FIXES - OutboxLimits.TRIMMED_FIXES).toLong(),
        )
    }

    @Test
    fun `queueing a fix asks the database once, not twice`() = runTest {
        val counted = object : OutboxDao by dao {
            var depths = 0
            var counts = 0
            override suspend fun depthOf(kind: String): QueueDepth {
                depths++
                return dao.depthOf(kind)
            }

            override suspend fun countOf(kind: String): Int {
                counts++
                return dao.countOf(kind)
            }

            override suspend fun oldestCreatedAt(kind: String): Long? {
                counts++
                return dao.oldestCreatedAt(kind)
            }
        }

        repeat(10) { LocationQueue(counted) { 5_000L }.record(listOf(fix(it)), 180) }

        assertEquals("a fix arrives every interval; it pays for one scan", 10, counted.depths)
        assertEquals("and not for a second one", 0, counted.counts)
    }

    @Test
    fun `a full location queue never costs a call its place`() = runTest {
        dao.insert(OutboxEntry(kind = OutboxKind.CALL_LOG, payload = "{}", createdAt = 0))
        fill(OutboxLimits.MAX_QUEUED_FIXES)

        queue().record(listOf(fix()), windowSeconds = 180)

        assertEquals(1, dao.countOf(OutboxKind.CALL_LOG))
    }

    @Test
    fun `a backlog does not ask for an upload once per fix`() = runTest {
        dao.insertAll(
            (0 until 5_000).map {
                OutboxEntry(
                    kind = OutboxKind.LOCATION,
                    payload = """{"latitude":1.0,"longitude":2.0,"timestamp":$it}""",
                    createdAt = it.toLong(),
                )
            },
        )
        val queue = LocationQueue(dao) { clock }

        var asked = 0
        repeat(240) {
            clock += 60_000
            if (queue.record(listOf(fix(it)), 180)) asked++
        }

        assertTrue(
            "behind a backlog every fix is over threshold and older than the window, " +
                "so an unguarded queue enqueues WorkManager 1,440 times a day: asked $asked",
            asked <= 240 / 3 + 1,
        )
        assertTrue("but it must still ask", asked > 0)
    }

    @Test
    fun `a window of zero still asks on every fix`() = runTest {
        val queue = LocationQueue(dao) { clock }

        var asked = 0
        repeat(5) {
            clock += 1_000
            if (queue.record(listOf(fix(it)), 0)) asked++
        }

        assertEquals("the documented request-per-fix mode is untouched", 5, asked)
    }
}

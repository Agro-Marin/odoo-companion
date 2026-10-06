package com.odoocompanion.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OutboxCostTest {
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
    fun tearDown() = database.close()

    private suspend fun fill(rows: Int) {
        dao.insertAll(
            (0 until rows).map { i ->
                OutboxEntry(
                    kind = OutboxKind.LOCATION,
                    payload = """{"latitude":1.0,"longitude":2.0,"timestamp":$i}""",
                    createdAt = i.toLong(),
                )
            },
        )
    }

    private inline fun millis(times: Int, block: () -> Unit): Double {
        block()
        val start = System.nanoTime()
        repeat(times) { block() }
        return (System.nanoTime() - start) / times / 1_000_000.0
    }

    @Test
    fun `the per-drain purge scan at the queue ceiling`() = runBlocking {
        fill(OutboxLimits.MAX_QUEUED_FIXES)

        val purge = millis(20) { runBlocking { dao.deadFilesBefore(0L) } }
        val countDead = millis(20) { runBlocking { dao.countDead() } }
        val take = millis(20) {
            runBlocking { dao.take(OutboxKind.LOCATION, 200) }
        }

        println(
            "COST rows=%d deadFilesBefore=%.2fms countDead=%.2fms take200=%.2fms".format(
                OutboxLimits.MAX_QUEUED_FIXES,
                purge,
                countDead,
                take,
            ),
        )

        assertTrue("purge scan is not a hot path: ${purge}ms", purge < 500)
    }
}

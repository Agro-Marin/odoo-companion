package com.odoocompanion.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadCadenceTest {
    private val now = 1_800_000_000_000L
    private val window = 180L

    private fun due(queued: Int, ageSeconds: Long) = UploadCadence.dueNow(
        queued = queued,
        oldestCreatedAt = now - ageSeconds * 1_000,
        windowSeconds = window,
        now = now,
    )

    @Test
    fun `a fresh fix on its own waits`() {
        assertFalse(due(queued = 1, ageSeconds = 0))
    }

    @Test
    fun `a fix older than the window goes`() {
        assertFalse(due(queued = 3, ageSeconds = window - 1))
        assertTrue(due(queued = 3, ageSeconds = window))
    }

    @Test
    fun `enough waiting fixes go before the window elapses`() {
        assertTrue(due(queued = UploadCadence.BATCH_THRESHOLD, ageSeconds = 0))
        assertFalse(due(queued = UploadCadence.BATCH_THRESHOLD - 1, ageSeconds = 0))
    }

    @Test
    fun `an empty queue is never worth a request`() {
        assertFalse(due(queued = 0, ageSeconds = 10_000))
        assertFalse(
            UploadCadence.dueNow(
                queued = 0,
                oldestCreatedAt = null,
                windowSeconds = window,
                now = now,
            ),
        )
    }

    @Test
    fun `a window of zero sends every fix, as before`() {
        assertTrue(
            UploadCadence.dueNow(
                queued = 1,
                oldestCreatedAt = now,
                windowSeconds = 0,
                now = now,
            ),
        )
    }

    @Test
    fun `a clock that moved backwards does not strand the queue`() {
        assertTrue(
            UploadCadence.dueNow(
                queued = 1,
                oldestCreatedAt = now + 60_000,
                windowSeconds = window,
                now = now,
            ),
        )
    }

    @Test
    fun `a day of fixes at the defaults costs a quarter of the requests`() {
        fun requestsPerDay(windowSeconds: Long): Int {
            val intervalSeconds = 60L
            var queued = 0
            var oldest = 0L
            var requests = 0
            for (tick in 0 until 24 * 60) {
                val clock = tick * intervalSeconds * 1_000
                if (queued == 0) oldest = clock
                queued++
                if (UploadCadence.dueNow(queued, oldest, windowSeconds, clock)) {
                    requests++
                    queued = 0
                }
            }
            return requests
        }

        val before = requestsPerDay(windowSeconds = 0)
        val after = requestsPerDay(windowSeconds = 180)
        println("CADENCE requests/day before=$before after=$after")

        assertEquals(1440, before)
        assertEquals(360, after)
    }
}

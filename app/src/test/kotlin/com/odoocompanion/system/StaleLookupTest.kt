package com.odoocompanion.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleLookupTest {
    private val every = StaleLookup.RETRY_MILLIS

    @Test
    fun `a lookup that never failed is not asked again`() {
        assertFalse(StaleLookup().dueAfterDelivery(now = 1_000))
    }

    @Test
    fun `the first delivery after a failure asks again at once`() {
        val lookup = StaleLookup()
        lookup.failed()

        assertTrue(lookup.dueAfterDelivery(now = 1_000))
    }

    @Test
    fun `an endpoint that keeps failing is asked at most once a period`() {
        val lookup = StaleLookup()
        lookup.failed()
        assertTrue(lookup.dueAfterDelivery(now = 0))
        lookup.failed()

        assertFalse(lookup.dueAfterDelivery(now = every - 1))
        assertTrue(lookup.dueAfterDelivery(now = every))
    }

    @Test
    fun `an answer ends the retries`() {
        val lookup = StaleLookup()
        lookup.failed()
        lookup.settled()

        assertFalse(lookup.dueAfterDelivery(now = every * 10))
    }

    @Test
    fun `a clock set backwards does not hold a retry for ever`() {
        val lookup = StaleLookup()
        lookup.failed()
        assertTrue(lookup.dueAfterDelivery(now = every * 10))

        assertTrue(lookup.dueAfterDelivery(now = every))
    }
}

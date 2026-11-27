package com.odoocompanion.system

import com.odoocompanion.net.HandsetHealth
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthCadenceTest {
    private val every = HealthCadence.REFRESH_MILLIS

    private val retry = HealthCadence.RETRY_MILLIS

    private val health = HandsetHealth(
        appVersion = "0.6.6 (13)",
        android = "15 (API 35)",
        model = "Google Pixel 5",
        blockers = listOf("battery_optimized"),
        queued = 4,
        undeliverable = 0,
    )

    private val sent = HealthCadence.fingerprint("phone-01@https://odoo", health)

    private fun due(
        changed: HandsetHealth = health,
        enrollment: String = "phone-01@https://odoo",
        at: Long = 1_000,
    ) = HealthCadence.due(
        HealthCadence.fingerprint(enrollment, changed),
        sent,
        attemptedAt = 0,
        taken = true,
        now = at,
    )

    @Test
    fun `a phone that never reported reports`() {
        assertTrue(HealthCadence.due(sent, null, attemptedAt = null, taken = false, now = 0))
    }

    @Test
    fun `nothing new is not sent again inside the refresh period`() {
        assertFalse(due())
        assertFalse(due(at = every - 1))
    }

    @Test
    fun `the same state is sent again once the period has passed`() {
        assertTrue(due(at = every))
    }

    @Test
    fun `a new blocker, version or error is sent at once`() {
        assertTrue(due(health.copy(blockers = emptyList())))
        assertTrue(due(health.copy(appVersion = "0.6.7 (14)")))
        assertTrue(due(health.copy(lastError = "auth rejected (401)")))
        assertTrue(due(health.copy(undeliverable = 1)))
    }

    @Test
    fun `the queue moving alone waits for the period`() {
        assertFalse(due(health.copy(queued = 400)))
    }

    @Test
    fun `a new enrolment reports to its server at once`() {
        assertTrue(due(enrollment = "phone-02@https://odoo"))
    }

    @Test
    fun `a clock set backwards does not hold the report for ever`() {
        assertTrue(due(at = -1))
    }

    @Test
    fun `a report odoo did not take is offered again in an hour, not on every drain`() {
        fun refused(at: Long) =
            HealthCadence.due(sent, sent, attemptedAt = 0, taken = false, now = at)

        assertFalse("a server without the route costs one request an hour", refused(retry - 1))
        assertTrue(refused(retry))
    }
}

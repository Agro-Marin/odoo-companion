package com.odoocompanion.ui

import com.odoocompanion.R
import com.odoocompanion.config.PAYLOAD_LIMIT_TTL_MILLIS
import com.odoocompanion.config.Settings
import com.odoocompanion.data.OutboxCounts
import com.odoocompanion.system.Blocker
import com.odoocompanion.system.HealthReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusScreenPolicyTest {
    private fun health(
        location: Boolean = true,
        background: Boolean = true,
        callLog: Boolean = true,
        storage: Boolean = true,
        battery: Boolean = true,
    ) = HealthReport(location, background, callLog, storage, battery)

    private val enrolled = Settings(
        baseUrl = "https://odoo.example.com",
        identifier = "phone-01",
        token = "t",
    )

    private val empty = OutboxCounts(0, 0, 0, 0)

    private fun lines(
        settings: Settings = enrolled,
        report: HealthReport = health(),
        queues: OutboxCounts = empty,
    ) = StatusScreen.lines(settings, report, queues)

    @Test
    fun `a healthy enrolled phone names no blocker`() {
        val shown = lines()

        assertEquals(StatusLine.Say(R.string.status_enrolled), shown.first())
        assertFalse(shown.any { it.text == StatusScreen.textFor(Blocker.LOCATION_MISSING) })
    }

    @Test
    fun `a cap too small for any recording names the provisioning mistake`() {
        val shown = StatusScreen.lines(
            enrolled.copy(maxPayloadBytes = 1024 * 1024, maxPayloadLearnedAt = 1),
            health(),
            empty,
            now = 2,
        )

        assertEquals(
            StatusLine.Detail(R.string.status_small_cap, "1024 KB"),
            shown.single { it.text == R.string.status_small_cap },
        )
        assertFalse(
            "what was learned a day ago may have been raised since",
            StatusScreen.lines(
                enrolled.copy(maxPayloadBytes = 1024 * 1024, maxPayloadLearnedAt = 0),
                health(),
                empty,
                now = PAYLOAD_LIMIT_TTL_MILLIS,
            ).any { it.text == R.string.status_small_cap },
        )
        assertFalse(
            StatusScreen.lines(
                enrolled.copy(maxPayloadBytes = 50L * 1024 * 1024, maxPayloadLearnedAt = 1),
                health(),
                empty,
                now = 2,
            ).any { it.text == R.string.status_small_cap },
        )
        assertFalse(lines().any { it.text == R.string.status_small_cap })
    }

    @Test
    fun `an error is shown beside the last delivery, not instead of it`() {
        val shown = lines(
            enrolled.copy(lastUploadAt = 500L, lastUploadError = "server 500"),
        )

        assertTrue(StatusLine.Detail(R.string.status_last_error, "server 500") in shown)
        assertTrue(StatusLine.Since(R.string.status_last_upload_ok, 500L) in shown)
    }

    @Test
    fun `an error with no delivery yet says there has been none`() {
        val shown = lines(enrolled.copy(lastUploadError = "auth rejected (401)"))

        assertTrue(StatusLine.Detail(R.string.status_last_error, "auth rejected (401)") in shown)
        assertTrue(StatusLine.Say(R.string.status_last_upload_never) in shown)
    }

    @Test
    fun `an attempt more recent than the last success is named separately`() {
        val quiet = lines(enrolled.copy(lastUploadAt = 900L, lastAttemptAt = 900L))
        val failing = lines(enrolled.copy(lastUploadAt = 900L, lastAttemptAt = 1_500L))

        assertFalse(quiet.any { it.text == R.string.status_last_attempt })
        assertTrue(StatusLine.Since(R.string.status_last_attempt, 1_500L) in failing)
    }

    @Test
    fun `the hint only appears with something to act on`() {
        assertFalse(lines().any { it.text == R.string.status_undeliverable_hint })

        val stuck = lines(queues = OutboxCounts(1, 2, 3, 4))

        assertTrue(StatusLine.Quantity(R.plurals.status_undeliverable, 4) in stuck)
        assertTrue(stuck.any { it.text == R.string.status_undeliverable_hint })
    }

    @Test
    fun `every queue is counted, including the empty ones`() {
        val shown = lines(queues = OutboxCounts(7, 8, 9, 0))

        assertTrue(StatusLine.Count(R.string.status_queued_positions, 7) in shown)
        assertTrue(StatusLine.Count(R.string.status_queued_calls, 8) in shown)
        assertTrue(StatusLine.Count(R.string.status_queued_recordings, 9) in shown)
    }

    @Test
    fun `a feature that is off never contributes its blocker`() {
        val bare = health(callLog = false, storage = false)

        val off = lines(
            enrolled.copy(callLogEnabled = false, recordingsEnabled = false),
            bare,
        )
        val on = lines(
            enrolled.copy(callLogEnabled = true, recordingsEnabled = true),
            bare,
        )

        assertFalse(
            off.any {
                it.text == StatusScreen.textFor(Blocker.CALL_LOG_PERMISSION_MISSING) ||
                    it.text == StatusScreen.textFor(Blocker.RECORDING_STORAGE_MISSING)
            },
        )
        assertTrue(on.any { it.text == StatusScreen.textFor(Blocker.CALL_LOG_PERMISSION_MISSING) })
        assertTrue(on.any { it.text == StatusScreen.textFor(Blocker.RECORDING_STORAGE_MISSING) })
    }

    @Test
    fun `every blocker has its own wording`() {
        val wordings = Blocker.entries.map(StatusScreen::textFor)

        assertEquals(Blocker.entries.size, wordings.toSet().size)
    }
}

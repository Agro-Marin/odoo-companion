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
        microphone: Boolean = true,
        localNetwork: Boolean = true,
    ) = HealthReport(location, background, callLog, storage, battery, microphone, localNetwork)

    private val enrolled = Settings(
        baseUrl = "https://odoo.example.com",
        identifier = "phone-01",
        token = "t",
    )

    private val empty = OutboxCounts(0, 0, 0, 0, 0)

    private fun lines(
        settings: Settings = enrolled,
        report: HealthReport = health(),
        queues: OutboxCounts = empty,
        softphoneWanted: Boolean = false,
        localNetworkWanted: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): List<StatusLine> {
        val panel =
            StatusScreen.panel(settings, report, queues, softphoneWanted, localNetworkWanted, now)
        return panel.alerts + listOfNotNull(panel.lastUpload, panel.lastError, panel.lastAttempt)
    }

    @Test
    fun `a healthy enrolled phone names no blocker`() {
        val shown = lines()

        assertTrue(
            StatusScreen.panel(
                enrolled,
                health(),
                empty,
                softphoneWanted = false,
                localNetworkWanted = false
            ).enrolled
        )
        assertFalse(shown.any { it.text == StatusScreen.textFor(Blocker.LOCATION_MISSING) })
    }

    @Test
    fun `a cap too small for any recording names the provisioning mistake`() {
        val shown = lines(
            enrolled.copy(maxPayloadBytes = 1024 * 1024, maxPayloadLearnedAt = 1),
            now = 2,
        )

        assertEquals(
            StatusLine.Detail(R.string.status_small_cap, "1024 KB"),
            shown.single { it.text == R.string.status_small_cap },
        )
        assertFalse(
            "what was learned a day ago may have been raised since",
            lines(
                enrolled.copy(maxPayloadBytes = 1024 * 1024, maxPayloadLearnedAt = 0),
                now = PAYLOAD_LIMIT_TTL_MILLIS,
            ).any { it.text == R.string.status_small_cap },
        )
        assertFalse(
            lines(
                enrolled.copy(maxPayloadBytes = 50L * 1024 * 1024, maxPayloadLearnedAt = 1),
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

        val stuck = lines(queues = OutboxCounts(1, 2, 3, 0, 4))

        assertTrue(StatusLine.Quantity(R.plurals.status_undeliverable, 4) in stuck)
        assertTrue(stuck.any { it.text == R.string.status_undeliverable_hint })
    }

    @Test
    fun `rows held for a previous enrolment are named by its server and device`() {
        val queues = empty.copy(held = 3, heldFor = "phone-01@https://old.example.com/odoo")

        val held = StatusScreen.panel(enrolled, health(), queues, false, false).held

        assertEquals(
            HeldQueue(3, "phone-01@https://old.example.com/odoo", "old.example.com/odoo, phone-01"),
            held,
        )
        assertEquals(
            "nothing to send them to before there is an enrolment",
            null,
            StatusScreen.panel(Settings(), health(), queues, false, false).held,
        )
    }

    @Test
    fun `every queue is counted, including the empty ones`() {
        val queues = OutboxCounts(7, 0, 9, 2, 0)

        assertEquals(queues, StatusScreen.panel(enrolled, health(), queues, false, false).queues)
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

    @Test
    fun `a company line that cannot ring for want of the microphone says so`() {
        val blocker = StatusScreen.textFor(Blocker.MICROPHONE_MISSING)

        assertTrue(
            lines(report = health(microphone = false), softphoneWanted = true)
                .any { it.text == blocker },
        )
        assertFalse(
            "no extension, no prompt",
            lines(report = health(microphone = false)).any { it.text == blocker },
        )
    }

    // Measured on an emulator: moved from one Odoo on a host to another on the
    // same host, the band named both "10.0.2.2", and could not say which
    // server the held rows were for.
    @Test
    fun `a held enrolment is named by the server it was for, port and path included`() {
        assertEquals(
            "10.0.2.2:18650, phone-01",
            StatusScreen.describe("phone-01@http://10.0.2.2:18650")
        )
        assertEquals(
            "odoo.example.com, phone-01",
            StatusScreen.describe(
                Settings("https://Odoo.Example.com:443/", "phone-01", "t").enrollment!!
            ),
        )
        assertEquals(
            "example.com/erp, phone-01",
            StatusScreen.describe("phone-01@https://example.com/erp")
        )
    }

    // From Android 17 a LAN Odoo without the grant times out: the queue grew
    // with only "timeout" as the last error.
    @Test
    fun `a local server without local network access says so`() {
        val blocker = StatusLine.Say(R.string.status_local_network_missing)

        assertTrue(
            blocker in lines(report = health(localNetwork = false), localNetworkWanted = true)
        )
        assertFalse(
            "a cloud server needs no such access",
            blocker in lines(report = health(localNetwork = false), localNetworkWanted = false),
        )
        assertFalse(
            blocker in lines(report = health(localNetwork = true), localNetworkWanted = true)
        )
    }
}

package com.odoocompanion.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.odoocompanion.R
import com.odoocompanion.config.Settings
import com.odoocompanion.data.OutboxCounts
import com.odoocompanion.system.Blocker
import com.odoocompanion.system.HealthReport

sealed interface StatusLine {
    // Deliberately unannotated: every variant but Quantity holds a @StringRes,
    // and Quantity holds a @PluralsRes. The annotation belongs on each of them,
    // where a smart cast still gives lint the right resource type.
    val text: Int

    data class Say(@StringRes override val text: Int) : StatusLine

    data class Count(@StringRes override val text: Int, val value: Int) : StatusLine

    data class Quantity(@PluralsRes override val text: Int, val value: Int) : StatusLine

    data class Since(@StringRes override val text: Int, val at: Long) : StatusLine

    data class Detail(@StringRes override val text: Int, val detail: String) : StatusLine
}

// What the status panel draws, grouped the way it is drawn: whether the phone
// is enrolled, what needs a hand (alerts), what it last did, and its queues.
data class StatusPanel(
    val enrolled: Boolean,
    val managed: Boolean,
    // a permission or exemption is missing: what the grant button is for
    val blocked: Boolean,
    val alerts: List<StatusLine>,
    val lastUpload: StatusLine,
    val lastError: StatusLine?,
    val lastAttempt: StatusLine?,
    val queues: OutboxCounts,
    val held: HeldQueue?,
)

// Rows collected for an enrolment the handset no longer has, and which one the
// newest of them is for: never sent to this server unless someone says so.
data class HeldQueue(val rows: Int, val enrollment: String, val collectedFor: String)

object StatusScreen {
    fun panel(
        settings: Settings,
        health: HealthReport,
        queues: OutboxCounts,
        softphoneWanted: Boolean,
        localNetworkWanted: Boolean,
        now: Long = System.currentTimeMillis(),
    ): StatusPanel {
        val blockers = health.blockers(
            settings.callLogEnabled,
            settings.recordingsEnabled,
            softphoneWanted,
            localNetworkWanted,
        )
        val alerts = buildList {
            val cap = settings.payloadLimit(now)
            if (cap in 1..<SMALLEST_MOBILE_CAP_BYTES) {
                add(StatusLine.Detail(R.string.status_small_cap, "${cap / 1024} KB"))
            }
            blockers.forEach { add(StatusLine.Say(textFor(it))) }
            if (queues.undeliverable > 0) {
                add(StatusLine.Quantity(R.plurals.status_undeliverable, queues.undeliverable))
                add(StatusLine.Say(R.string.status_undeliverable_hint))
            }
        }
        return StatusPanel(
            enrolled = settings.isEnrolled,
            managed = settings.managed,
            blocked = blockers.isNotEmpty(),
            alerts = alerts,
            lastUpload = lastUpload(settings),
            lastError = settings.lastUploadError?.let {
                StatusLine.Detail(R.string.status_last_error, it)
            },
            lastAttempt = StatusLine.Since(R.string.status_last_attempt, settings.lastAttemptAt)
                .takeIf { settings.lastAttemptAt > settings.lastUploadAt },
            queues = queues,
            held = queues.heldFor
                ?.takeIf { queues.held > 0 && settings.enrollment != null }
                ?.let { HeldQueue(queues.held, it, describe(it)) },
        )
    }

    // "odoo.example.com, phone-17": an enrolment as a person knows it. The base
    // URL in it is already canonical, so what is left after the scheme -- a
    // port that is not the default, a path -- is part of which server it was:
    // the host alone named two Odoos on one machine alike.
    fun describe(enrollment: String): String {
        val server = enrollment.substringAfter('@').substringAfter("://")
        return "$server, ${enrollment.substringBefore('@')}"
    }

    const val SMALLEST_MOBILE_CAP_BYTES = 8L * 1024 * 1024

    @StringRes
    fun textFor(blocker: Blocker): Int = when (blocker) {
        Blocker.LOCATION_MISSING -> R.string.status_location_missing
        Blocker.BACKGROUND_LOCATION_MISSING -> R.string.status_background_location_missing
        Blocker.BATTERY_OPTIMIZED -> R.string.status_battery_optimized
        Blocker.CALL_LOG_PERMISSION_MISSING -> R.string.status_call_log_permission_missing
        Blocker.RECORDING_STORAGE_MISSING -> R.string.status_recording_storage_missing
        Blocker.MICROPHONE_MISSING -> R.string.status_microphone_missing
        Blocker.LOCAL_NETWORK_MISSING -> R.string.status_local_network_missing
    }

    // The last delivery and the last error are two lines, not one: a drain can
    // deliver every position and still defer the one recording the server
    // raised on, and a single line either hid the error or called a working
    // phone failed.
    private fun lastUpload(settings: Settings): StatusLine = if (settings.lastUploadAt > 0L) {
        StatusLine.Since(R.string.status_last_upload_ok, settings.lastUploadAt)
    } else {
        StatusLine.Say(R.string.status_last_upload_never)
    }
}

package com.odoocompanion.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.odoocompanion.R
import com.odoocompanion.config.Settings
import com.odoocompanion.system.Blocker
import com.odoocompanion.system.HealthReport

data class QueueDepths(
    val positions: Int,
    val calls: Int,
    val recordings: Int,
    val undeliverable: Int,
)

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

object StatusScreen {
    fun lines(
        settings: Settings,
        health: HealthReport,
        queues: QueueDepths,
        now: Long = System.currentTimeMillis(),
    ): List<StatusLine> = buildList {
        val enrolment =
            if (settings.isEnrolled) R.string.status_enrolled else R.string.status_not_enrolled
        add(StatusLine.Say(enrolment))
        if (settings.managed) add(StatusLine.Say(R.string.status_managed))
        val cap = settings.payloadLimit(now)
        if (cap in 1..<SMALLEST_MOBILE_CAP_BYTES) {
            add(StatusLine.Detail(R.string.status_small_cap, "${cap / 1024} KB"))
        }
        health.blockers(settings.callLogEnabled, settings.recordingsEnabled)
            .forEach { add(StatusLine.Say(textFor(it))) }

        add(lastUpload(settings))
        if (settings.lastAttemptAt > settings.lastUploadAt) {
            add(StatusLine.Since(R.string.status_last_attempt, settings.lastAttemptAt))
        }

        add(StatusLine.Count(R.string.status_queued_positions, queues.positions))
        add(StatusLine.Count(R.string.status_queued_calls, queues.calls))
        add(StatusLine.Count(R.string.status_queued_recordings, queues.recordings))

        if (queues.undeliverable > 0) {
            add(StatusLine.Quantity(R.plurals.status_undeliverable, queues.undeliverable))
            add(StatusLine.Say(R.string.status_undeliverable_hint))
        }
    }

    const val SMALLEST_MOBILE_CAP_BYTES = 8L * 1024 * 1024

    @StringRes
    fun textFor(blocker: Blocker): Int = when (blocker) {
        Blocker.LOCATION_MISSING -> R.string.status_location_missing
        Blocker.BACKGROUND_LOCATION_MISSING -> R.string.status_background_location_missing
        Blocker.BATTERY_OPTIMIZED -> R.string.status_battery_optimized
        Blocker.CALL_LOG_PERMISSION_MISSING -> R.string.status_call_log_permission_missing
        Blocker.RECORDING_STORAGE_MISSING -> R.string.status_recording_storage_missing
    }

    private fun lastUpload(settings: Settings): StatusLine = when {
        settings.lastUploadError != null ->
            StatusLine.Detail(R.string.status_last_upload_failed, settings.lastUploadError)

        settings.lastUploadAt > 0L ->
            StatusLine.Since(R.string.status_last_upload_ok, settings.lastUploadAt)

        else -> StatusLine.Say(R.string.status_last_upload_never)
    }
}

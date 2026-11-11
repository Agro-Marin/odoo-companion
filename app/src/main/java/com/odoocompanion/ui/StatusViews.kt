package com.odoocompanion.ui

import android.content.Context
import android.content.res.ColorStateList
import android.text.format.DateUtils
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import com.odoocompanion.CompanionApp
import com.odoocompanion.R
import com.odoocompanion.config.Settings
import com.odoocompanion.data.OutboxCounts
import com.odoocompanion.databinding.AlertBandBinding
import com.odoocompanion.databinding.StatePillBinding
import com.odoocompanion.system.DeviceHealth
import com.odoocompanion.system.LocalNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

// The status panel as text, formatted before it is compared, so the minute
// tick redraws "2 minutes ago" while an unchanged panel draws nothing. Home
// reads the enrolment and the alerts from it; Settings reads all of it.
data class PanelText(
    val enrolled: Boolean,
    val managed: Boolean,
    val blocked: Boolean,
    val alerts: List<String>,
    val lastUpload: String,
    val lastAttempt: String?,
    val lastError: String?,
    val queues: OutboxCounts,
    val held: HeldQueue?,
)

object StatusText {
    fun panel(
        context: Context,
        settings: Settings,
        counts: OutboxCounts,
        refused: Int?,
        softphoneWanted: Boolean,
        localNetworkWanted: Boolean,
    ): PanelText {
        val panel = StatusScreen.panel(
            settings,
            DeviceHealth.report(context),
            counts,
            softphoneWanted,
            localNetworkWanted,
        )
        val format = { line: StatusLine -> format(context, line) }
        return PanelText(
            enrolled = panel.enrolled,
            managed = panel.managed,
            blocked = panel.blocked,
            alerts = listOfNotNull(refused?.let(context::getString)) + panel.alerts.map(format),
            lastUpload = format(panel.lastUpload),
            lastAttempt = panel.lastAttempt?.let(format),
            lastError = panel.lastError?.let(format),
            queues = panel.queues,
            held = panel.held,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun queueCounts(app: CompanionApp): Flow<OutboxCounts> = app.config.settings
        .map { it.enrollment }
        .distinctUntilChanged()
        .flatMapLatest { app.database.outbox().counts(it) }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun localNetworkWanted(app: CompanionApp, checks: Flow<*>): Flow<Boolean> = combine(
        app.config.settings.map { it.baseUrl.takeIf { _ -> it.isEnrolled } },
        app.softphones.domain,
        checks,
    ) { baseUrl, domain, _ -> baseUrl to domain }
        .mapLatest { (baseUrl, domain) -> LocalNetwork.anyLocal(baseUrl, domain) }
        .flowOn(Dispatchers.IO)
        .distinctUntilChanged()

    suspend fun localNetworkWanted(app: CompanionApp, settings: Settings): Boolean =
        withContext(Dispatchers.IO) {
            LocalNetwork.anyLocal(
                settings.baseUrl.takeIf { settings.isEnrolled },
                app.softphones.current()?.domain,
            )
        }

    private fun format(context: Context, line: StatusLine): String = when (line) {
        is StatusLine.Say -> context.getString(line.text)

        is StatusLine.Count -> context.getString(line.text, line.value)

        is StatusLine.Quantity ->
            context.resources.getQuantityString(line.text, line.value, line.value)

        is StatusLine.Detail -> context.getString(line.text, line.detail)

        is StatusLine.Since ->
            context.getString(line.text, DateUtils.getRelativeTimeSpanString(line.at))
    }
}

// Whether this phone is enrolled: the dot in the accent, or in the alert's
// ink while it is not.
fun StatePillBinding.show(enrolled: Boolean) {
    stateText.setText(if (enrolled) R.string.status_enrolled else R.string.status_not_enrolled)
    val dot = if (enrolled) R.color.brand_accent else R.color.alert_ink
    ImageViewCompat.setImageTintList(
        stateDot,
        ColorStateList.valueOf(ContextCompat.getColor(root.context, dot)),
    )
}

// One band however many alerts there are, so the screen keeps its shape: the
// first one and how many follow, unfolded on a tap. Every one of them is read
// aloud either way.
class AlertBand(private val band: AlertBandBinding, onGrant: () -> Unit) {
    private var shown: List<String> = emptyList()
    private var blocked = false
    private var unfolded = false

    init {
        band.root.setOnClickListener {
            unfolded = !unfolded
            show(shown, blocked)
        }
        band.alertGrant.setOnClickListener { onGrant() }
    }

    fun show(alerts: List<String>, blocked: Boolean) {
        shown = alerts
        this.blocked = blocked
        band.root.isVisible = alerts.isNotEmpty()
        band.alertGrant.isVisible = blocked
        band.alertText.contentDescription = alerts.joinToString("\n")
        band.alertText.text = when {
            alerts.size <= 1 || unfolded -> alerts.joinToString("\n")

            else -> band.root.context.getString(
                R.string.alerts_more,
                alerts.first(),
                alerts.size - 1,
            )
        }
    }
}

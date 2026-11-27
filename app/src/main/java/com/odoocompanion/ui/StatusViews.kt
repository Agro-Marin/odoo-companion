package com.odoocompanion.ui

import android.content.Context
import android.content.pm.PackageInfo
import android.content.res.ColorStateList
import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import com.odoocompanion.CompanionApp
import com.odoocompanion.R
import com.odoocompanion.config.Settings
import com.odoocompanion.data.OutboxCounts
import com.odoocompanion.data.UploadState
import com.odoocompanion.databinding.AlertBandBinding
import com.odoocompanion.databinding.StatePillBinding
import com.odoocompanion.system.DeviceHealth
import com.odoocompanion.system.LocalNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

internal fun Context.versionText(info: PackageInfo): String =
    getString(R.string.app_version, info.versionName.orEmpty(), info.longVersionCode)

internal val statusMinutes: Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(DateUtils.MINUTE_IN_MILLIS)
    }
}

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
    @OptIn(ExperimentalCoroutinesApi::class)
    fun panels(
        context: Context,
        checks: Flow<*>,
        refusal: Flow<Int?> = flowOf(null),
    ): Flow<PanelText> {
        val app = CompanionApp.from(context)
        val counts = app.config.settings.map { it.enrollment }.distinctUntilChanged()
            .flatMapLatest { owner ->
                app.database.outbox().counts(owner).map { owner to it }
            }
        return combine(
            app.config.settings,
            counts,
            refusal,
            merge(checks, statusMinutes),
            combine(app.softphones.configured, localNetworkWanted(app, checks), ::Pair),
        ) { settings, (owner, counts), refused, _, (softphone, localNetwork) ->
            if (settings.enrollment == owner) {
                panel(context, settings, counts, refused, softphone, localNetwork)
            } else {
                null
            }
        }.filterNotNull().distinctUntilChanged()
    }

    private fun panel(
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
    fun localNetworkWanted(app: CompanionApp, checks: Flow<*>): Flow<Boolean> = combine(
        app.config.settings.map { if (it.isEnrolled) it.baseUrl else null }.distinctUntilChanged(),
        app.softphones.domain,
        checks,
    ) { baseUrl, domain, _ -> baseUrl to domain }
        .mapLatest { (baseUrl, domain) -> LocalNetwork.anyLocal(baseUrl, domain) }
        .flowOn(Dispatchers.IO)
        .onStart { emit(false) }
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

        is StatusLine.Quantity ->
            context.resources.getQuantityString(line.text, line.value, line.value)

        is StatusLine.Detail -> context.getString(line.text, line.detail)

        is StatusLine.Since ->
            context.getString(line.text, DateUtils.getRelativeTimeSpanString(line.at))
    }
}

fun StatePillBinding.show(enrolled: Boolean) {
    stateText.setText(if (enrolled) R.string.status_enrolled else R.string.status_not_enrolled)
    val dot = if (enrolled) R.color.brand_accent else R.color.alert_ink
    ImageViewCompat.setImageTintList(
        stateDot,
        ColorStateList.valueOf(ContextCompat.getColor(root.context, dot)),
    )
}

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

@get:StringRes
internal val UploadState.labelResource: Int
    get() = when (this) {
        UploadState.WAITING -> R.string.whatsapp_upload_waiting
        UploadState.QUEUED -> R.string.whatsapp_upload_queued
        UploadState.DELIVERED -> R.string.whatsapp_upload_done
        UploadState.SET_ASIDE -> R.string.whatsapp_upload_failed
        UploadState.GONE -> R.string.whatsapp_upload_gone
        UploadState.WITHDRAWN -> R.string.whatsapp_upload_withdrawn
        UploadState.UNCONFIRMED -> R.string.upload_unconfirmed
    }

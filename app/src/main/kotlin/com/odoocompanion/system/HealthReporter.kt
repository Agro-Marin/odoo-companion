package com.odoocompanion.system

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import com.odoocompanion.BuildConfig
import com.odoocompanion.CompanionApp
import com.odoocompanion.config.Settings
import com.odoocompanion.net.HandsetHealth
import com.odoocompanion.softphone.SoftphoneCalls
import com.odoocompanion.softphone.SoftphoneSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException

object HealthReporter {
    suspend fun reportIfDue(
        app: CompanionApp,
        settings: Settings,
        lastError: String?,
        now: Long = System.currentTimeMillis(),
    ) {
        val health = gather(app, settings, lastError)
        val fingerprint = HealthCadence.fingerprint(settings.enrollment, health)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val attemptedAt = prefs.getLong(KEY_ATTEMPTED_AT, NEVER).takeIf { it != NEVER }
        val due = HealthCadence.due(
            fingerprint,
            prefs.getString(KEY_ATTEMPTED, null),
            attemptedAt,
            prefs.getBoolean(KEY_TAKEN, false),
            now,
        )
        if (!due) return
        val taken = app.client.reportHealth(settings, health)
        prefs.edit {
            putString(KEY_ATTEMPTED, fingerprint)
            putLong(KEY_ATTEMPTED_AT, now)
            putBoolean(KEY_TAKEN, taken)
        }
        if (taken) {
            debug(TAG) { "health reported: ${health.blockers.size} blocker(s)" }
        } else {
            Log.i(TAG, "Odoo did not take the health report; it is offered again in an hour")
        }
    }

    private suspend fun gather(
        app: CompanionApp,
        settings: Settings,
        lastError: String?,
    ): HandsetHealth {
        val softphone = softphoneOf(app)
        val counts = app.database.outbox().counts(settings.enrollment).first()
        val localNetworkWanted = withContext(Dispatchers.IO) {
            LocalNetwork.anyLocal(
                settings.baseUrl.takeIf { settings.isEnrolled },
                softphone?.domain,
            )
        }
        val blockers = DeviceHealth.report(app).blockers(
            callLogWanted = settings.callLogEnabled,
            recordingsWanted = settings.recordingsEnabled,
            softphoneWanted = softphone != null,
            localNetworkWanted = localNetworkWanted,
        ).map { it.name.lowercase() }
        return HandsetHealth(
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            model = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }
                .joinToString(" "),
            blockers = blockers + listOfNotNull(PAUSED.takeIf { ReportingPaused.pending }),
            queued = counts.positions + counts.calls + counts.recordings + counts.documents,
            undeliverable = counts.undeliverable,
            lastError = lastError,
            softphone = SoftphoneCalls.registration?.name?.lowercase()
                ?.takeIf { softphone != null },
        )
    }

    private suspend fun softphoneOf(app: CompanionApp): SoftphoneSettings? = try {
        app.softphones.current()
    } catch (unreadable: IOException) {
        Log.w(TAG, "Cannot read the softphone settings for the health report", unreadable)
        null
    }

    private const val TAG = "HealthReporter"
    private const val PREFS = "health"
    private const val KEY_ATTEMPTED = "attempted"
    private const val KEY_ATTEMPTED_AT = "attempted_at"
    private const val KEY_TAKEN = "taken"
    private const val NEVER = -1L
    private const val PAUSED = "reporting_paused"
}

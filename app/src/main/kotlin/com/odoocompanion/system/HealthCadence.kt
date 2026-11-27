package com.odoocompanion.system

import com.odoocompanion.net.HandsetHealth
import com.odoocompanion.net.WireJson

internal object HealthCadence {
    const val REFRESH_MILLIS = 6L * 60 * 60 * 1000

    const val RETRY_MILLIS = 60L * 60 * 1000

    fun fingerprint(enrollment: String?, health: HandsetHealth): String {
        val state = WireJson.encodeToString(HandsetHealth.serializer(), health.copy(queued = 0))
        return "$enrollment|$state"
    }

    fun due(
        fingerprint: String,
        attempted: String?,
        attemptedAt: Long?,
        taken: Boolean,
        now: Long,
    ): Boolean {
        if (fingerprint != attempted || attemptedAt == null) return true
        return now - attemptedAt !in 0..<if (taken) REFRESH_MILLIS else RETRY_MILLIS
    }
}

package com.odoocompanion.config

import android.content.Context
import android.content.RestrictionsManager
import android.os.Bundle
import android.util.Log

data class ManagedValues(
    val baseUrl: String? = null,
    val identifier: String? = null,
    val token: String? = null,
    val callLogEnabled: Boolean? = null,
    val recordingsEnabled: Boolean? = null,
    val wifiOnlyUploads: Boolean? = null,
    val locationIntervalSeconds: Long? = null,
    val uploadWindowSeconds: Long? = null,
) {
    val isEmpty: Boolean
        get() = baseUrl == null &&
            identifier == null &&
            token == null &&
            callLogEnabled == null &&
            recordingsEnabled == null &&
            wifiOnlyUploads == null &&
            locationIntervalSeconds == null &&
            uploadWindowSeconds == null
}

object ManagedConfig {
    fun read(context: Context): ManagedValues? {
        val manager = context.getSystemService(Context.RESTRICTIONS_SERVICE) as? RestrictionsManager
            ?: return unavailable()
        val restrictions = runCatching { manager.applicationRestrictions }
            .getOrElse { return unavailable() }
        return fromBundle(restrictions)
    }

    private fun unavailable(): ManagedValues? {
        Log.w(TAG, "No restrictions service; leaving the managed configuration as it stands")
        return null
    }

    fun fromBundle(bundle: Bundle?): ManagedValues {
        if (bundle == null || bundle.isEmpty) return ManagedValues()
        return ManagedValues(
            baseUrl = bundle.nonBlank("base_url")?.takeIf(::isUsableBaseUrl),
            identifier = bundle.nonBlank("identifier")?.takeIf(::isUsableIdentifier),
            token = bundle.nonBlank("token"),
            callLogEnabled = bundle.booleanOrNull("call_log_enabled"),
            recordingsEnabled = bundle.booleanOrNull("recordings_enabled"),
            wifiOnlyUploads = bundle.booleanOrNull("wifi_only_uploads"),
            locationIntervalSeconds = bundle.intervalSecondsOrNull("location_interval_seconds"),
            uploadWindowSeconds = bundle.secondsOrNull(
                "upload_window_seconds",
                minimum = MIN_UPLOAD_WINDOW_SECONDS,
                maximum = MAX_UPLOAD_WINDOW_SECONDS,
            ),
        )
    }

    private fun Bundle.nonBlank(key: String): String? =
        getString(key)?.trim()?.takeIf { it.isNotEmpty() }

    @Suppress("DEPRECATION")
    private fun Bundle.booleanOrNull(key: String): Boolean? {
        if (!containsKey(key)) return null
        return when (val value = get(key)) {
            is Boolean -> value

            is Number -> value.toInt() != 0

            is CharSequence -> value.toString().trim().lowercase().let(TRUTH::get)
                ?: unusable(key)

            else -> unusable(key)
        }
    }

    private fun unusable(key: String): Boolean? {
        Log.w(TAG, "Ignoring unusable managed $key; leaving the stored value alone")
        return null
    }

    private val TRUTH = mapOf(
        "true" to true, "1" to true, "yes" to true, "on" to true, "y" to true,
        "false" to false, "0" to false, "no" to false, "off" to false, "n" to false,
    )

    private fun Bundle.intervalSecondsOrNull(key: String): Long? = secondsOrNull(
        key,
        minimum = MIN_LOCATION_INTERVAL_SECONDS,
        maximum = MAX_LOCATION_INTERVAL_SECONDS,
    )

    @Suppress("DEPRECATION")
    private fun Bundle.secondsOrNull(key: String, minimum: Long, maximum: Long): Long? {
        if (!containsKey(key)) return null
        val raw = when (val value = get(key)) {
            is Number -> value.toLong()
            is CharSequence -> value.toString().trim().toLongOrNull()
            else -> null
        }

        if (raw == null || raw < 0 || (raw == 0L && minimum > 0)) {
            Log.w(TAG, "Ignoring unusable managed $key: ${get(key)}")
            return null
        }
        val clamped = raw.coerceIn(minimum, maximum)
        if (clamped != raw) {
            Log.w(TAG, "Managed $key of $raw s is out of range; using $clamped s")
        }
        return clamped
    }

    private const val TAG = "ManagedConfig"
}

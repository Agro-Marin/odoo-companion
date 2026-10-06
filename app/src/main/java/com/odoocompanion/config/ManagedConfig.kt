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
    val minMoveMetres: Long? = null,
    val policyPresent: Boolean = false,
    // The restriction keys the bundle actually carried, whatever their values
    // turned out to be worth. A key that arrived unusable is still a key the
    // administrator is managing; a key that never arrived is not.
    val presentKeys: Set<String> = emptySet(),
)

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
            baseUrl = bundle.nonBlank(ManagedKey.BASE_URL)?.takeIf(::isUsableBaseUrl),
            identifier = bundle.nonBlank(ManagedKey.IDENTIFIER)?.takeIf(::isUsableIdentifier),
            token = bundle.nonBlank(ManagedKey.TOKEN),
            callLogEnabled = bundle.booleanOrNull(ManagedKey.CALL_LOG_ENABLED),
            recordingsEnabled = bundle.booleanOrNull(ManagedKey.RECORDINGS_ENABLED),
            wifiOnlyUploads = bundle.booleanOrNull(ManagedKey.WIFI_ONLY_UPLOADS),
            locationIntervalSeconds = bundle.boundedOrNull(
                ManagedKey.LOCATION_INTERVAL_SECONDS,
                MIN_LOCATION_INTERVAL_SECONDS..MAX_LOCATION_INTERVAL_SECONDS,
                unit = "s",
            ),
            uploadWindowSeconds = bundle.boundedOrNull(
                ManagedKey.UPLOAD_WINDOW_SECONDS,
                MIN_UPLOAD_WINDOW_SECONDS..MAX_UPLOAD_WINDOW_SECONDS,
                unit = "s",
            ),
            minMoveMetres = bundle.boundedOrNull(
                ManagedKey.MIN_MOVE_METRES,
                DEFAULT_MIN_MOVE_METRES..MAX_MIN_MOVE_METRES,
                unit = "m",
            ),
            policyPresent = true,
            presentKeys = ManagedKey.ALL.filterTo(mutableSetOf(), bundle::containsKey),
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

    // A number, or a number in a string, clamped rather than refused.
    @Suppress("DEPRECATION")
    private fun Bundle.boundedOrNull(key: String, bounds: LongRange, unit: String): Long? {
        if (!containsKey(key)) return null
        val raw = when (val value = get(key)) {
            is Number -> value.toLong()
            is CharSequence -> value.toString().trim().toLongOrNull()
            else -> null
        }

        if (raw == null || raw < 0 || (raw == 0L && bounds.first > 0)) {
            Log.w(TAG, "Ignoring unusable managed $key: ${get(key)}")
            return null
        }
        val clamped = raw.coerceIn(bounds)
        if (clamped != raw) {
            Log.w(TAG, "Managed $key of $raw $unit is out of range; using $clamped $unit")
        }
        return clamped
    }

    private const val TAG = "ManagedConfig"
}

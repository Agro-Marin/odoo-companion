package com.odoocompanion.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class Settings(
    val baseUrl: String = "",
    val identifier: String = "",
    val token: String = "",
    val locationIntervalSeconds: Long = DEFAULT_LOCATION_INTERVAL_SECONDS,
    val uploadWindowSeconds: Long = DEFAULT_UPLOAD_WINDOW_SECONDS,
    val wifiOnlyUploads: Boolean = false,
    val callLogEnabled: Boolean = true,
    val recordingsEnabled: Boolean = false,
    val managed: Boolean = false,
    val maxPayloadBytes: Long = 0,
    val lastUploadAt: Long = 0,
    val lastAttemptAt: Long = 0,
    val lastUploadError: String? = null,
) {
    val isEnrolled: Boolean
        get() = baseUrl.isNotBlank() && identifier.isNotBlank() && token.isNotBlank()

    override fun toString(): String =
        "Settings(baseUrl=$baseUrl, identifier=$identifier, token=${token.redacted()}, " +
            "locationIntervalSeconds=$locationIntervalSeconds, " +
            "uploadWindowSeconds=$uploadWindowSeconds, wifiOnlyUploads=$wifiOnlyUploads, " +
            "callLogEnabled=$callLogEnabled, recordingsEnabled=$recordingsEnabled, " +
            "managed=$managed, maxPayloadBytes=$maxPayloadBytes, " +
            "lastUploadAt=$lastUploadAt, lastAttemptAt=$lastAttemptAt, " +
            "lastUploadError=$lastUploadError)"

    fun endpoint(suffix: String): String = baseUrl.trim().toHttpUrl()
        .newBuilder()
        .addPathSegment("remote")
        .addPathSegment("mobile")
        .addPathSegment(identifier)
        .addPathSegment(suffix)
        .build()
        .toString()
}

private fun String.redacted(): String = if (isEmpty()) "" else "***($length chars)"

const val DEFAULT_LOCATION_INTERVAL_SECONDS = 60L

const val MIN_LOCATION_INTERVAL_SECONDS = 15L
const val MAX_LOCATION_INTERVAL_SECONDS = 24L * 60 * 60

const val DEFAULT_UPLOAD_WINDOW_SECONDS = 180L
const val MAX_UPLOAD_WINDOW_SECONDS = 3600L
const val MIN_UPLOAD_WINDOW_SECONDS = 0L

sealed interface EnrollmentResult {
    data object Saved : EnrollmentResult
    data object InvalidBaseUrl : EnrollmentResult
    data object InvalidIdentifier : EnrollmentResult
}

fun isUsableBaseUrl(value: String): Boolean = value.trim().toHttpUrlOrNull() != null

fun isUsableIdentifier(value: String): Boolean =
    value.trim().let { it.isNotEmpty() && IDENTIFIER_PATTERN.matches(it) }

private val IDENTIFIER_PATTERN = Regex("[A-Za-z0-9._~-]+")

class DeviceConfig(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(storeFor(context))

    val settings: Flow<Settings> = store.data.map { it.toSettings() }

    private fun Preferences.toSettings() = Settings(
        baseUrl = this[BASE_URL].orEmpty(),
        identifier = this[IDENTIFIER].orEmpty(),
        token = this[TOKEN].orEmpty(),
        locationIntervalSeconds = this[LOCATION_INTERVAL] ?: DEFAULT_LOCATION_INTERVAL_SECONDS,
        uploadWindowSeconds = this[UPLOAD_WINDOW] ?: DEFAULT_UPLOAD_WINDOW_SECONDS,
        wifiOnlyUploads = this[WIFI_ONLY] ?: false,
        callLogEnabled = this[CALL_LOG_ENABLED] ?: true,
        recordingsEnabled = this[RECORDINGS_ENABLED] ?: false,
        managed = this[MANAGED] ?: false,
        maxPayloadBytes = this[MAX_PAYLOAD] ?: 0,
        lastUploadAt = this[LAST_UPLOAD_AT] ?: 0,
        lastAttemptAt = this[LAST_ATTEMPT_AT] ?: 0,
        lastUploadError = this[LAST_UPLOAD_ERROR],
    )

    suspend fun current(): Settings = settings.first()

    suspend fun saveEnrollment(
        baseUrl: String,
        identifier: String,
        token: String,
    ): EnrollmentResult {
        if (!isUsableBaseUrl(baseUrl)) return EnrollmentResult.InvalidBaseUrl
        if (!isUsableIdentifier(identifier)) return EnrollmentResult.InvalidIdentifier
        store.edit { prefs ->
            prefs[BASE_URL] = baseUrl.trim()
            prefs[IDENTIFIER] = identifier.trim()
            prefs[TOKEN] = token.trim()
        }
        return EnrollmentResult.Saved
    }

    suspend fun applyManaged(values: ManagedValues): Boolean {
        var changed = false
        store.edit { prefs ->
            val before = prefs.toSettings().managedFacet()

            prefs[MANAGED] = !values.isEmpty
            values.baseUrl?.let { prefs[BASE_URL] = it }
            values.identifier?.let { prefs[IDENTIFIER] = it }
            values.token?.let { prefs[TOKEN] = it }
            values.callLogEnabled?.let { prefs[CALL_LOG_ENABLED] = it }
            values.recordingsEnabled?.let { prefs[RECORDINGS_ENABLED] = it }
            values.wifiOnlyUploads?.let { prefs[WIFI_ONLY] = it }

            values.uploadWindowSeconds?.let { prefs[UPLOAD_WINDOW] = it }
            values.locationIntervalSeconds?.let { prefs[LOCATION_INTERVAL] = it }
            changed = prefs.toSettings().managedFacet() != before
        }
        return changed
    }

    private data class ManagedFacet(
        val managed: Boolean,
        val baseUrl: String,
        val identifier: String,
        val token: String,
        val callLogEnabled: Boolean,
        val recordingsEnabled: Boolean,
        val wifiOnlyUploads: Boolean,
        val uploadWindowSeconds: Long,
        val locationIntervalSeconds: Long,
    )

    private fun Settings.managedFacet() = ManagedFacet(
        managed = managed,
        baseUrl = baseUrl,
        identifier = identifier,
        token = token,
        callLogEnabled = callLogEnabled,
        recordingsEnabled = recordingsEnabled,
        wifiOnlyUploads = wifiOnlyUploads,
        uploadWindowSeconds = uploadWindowSeconds,
        locationIntervalSeconds = locationIntervalSeconds,
    )

    internal suspend fun clearEnrollment() {
        store.edit { prefs ->
            prefs.remove(BASE_URL)
            prefs.remove(IDENTIFIER)
            prefs.remove(TOKEN)
        }
    }

    suspend fun setFeature(callLog: Boolean? = null, recordings: Boolean? = null) {
        store.edit { prefs ->
            callLog?.let { prefs[CALL_LOG_ENABLED] = it }
            recordings?.let { prefs[RECORDINGS_ENABLED] = it }
        }
    }

    suspend fun setWifiOnlyUploads(enabled: Boolean) {
        store.edit { prefs -> prefs[WIFI_ONLY] = enabled }
    }

    suspend fun setUploadWindow(seconds: Long) {
        store.edit { prefs ->
            prefs[UPLOAD_WINDOW] =
                seconds.coerceIn(MIN_UPLOAD_WINDOW_SECONDS, MAX_UPLOAD_WINDOW_SECONDS)
        }
    }

    suspend fun setLocationInterval(seconds: Long) {
        store.edit { prefs ->
            prefs[LOCATION_INTERVAL] =
                seconds.coerceIn(MIN_LOCATION_INTERVAL_SECONDS, MAX_LOCATION_INTERVAL_SECONDS)
        }
    }

    suspend fun consumeBuildChange(version: Long): Boolean {
        var changed = false
        store.edit { prefs ->
            changed = prefs[LAST_BUILD] != version
            if (changed) prefs[LAST_BUILD] = version
        }
        return changed
    }

    suspend fun learnPayloadLimit(bytes: Long) {
        store.edit { prefs ->
            if (bytes > 0) prefs[MAX_PAYLOAD] = bytes else prefs.remove(MAX_PAYLOAD)
        }
    }

    suspend fun recordUpload(at: Long, delivered: Boolean, error: String?) {
        store.edit { prefs ->

            prefs[LAST_ATTEMPT_AT] = at
            if (error != null) {
                prefs[LAST_UPLOAD_ERROR] = error
                return@edit
            }
            prefs.remove(LAST_UPLOAD_ERROR)
            if (delivered) prefs[LAST_UPLOAD_AT] = at
        }
    }

    suspend fun callLogCursor(): Long = store.data.first()[CALL_LOG_CURSOR] ?: 0L

    suspend fun setCallLogCursor(value: Long) {
        store.edit { prefs -> prefs[CALL_LOG_CURSOR] = value }
    }

    suspend fun recordingCursor(): Long = store.data.first()[RECORDING_CURSOR] ?: 0L

    suspend fun setRecordingCursor(value: Long) {
        store.edit { prefs -> prefs[RECORDING_CURSOR] = value }
    }

    companion object {
        internal const val STORE_NAME = "companion_config"

        internal fun storeFor(context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            ) { context.preferencesDataStoreFile(STORE_NAME) }

        private val BASE_URL = stringPreferencesKey("base_url")
        private val IDENTIFIER = stringPreferencesKey("identifier")
        private val TOKEN = stringPreferencesKey("token")
        private val LOCATION_INTERVAL = longPreferencesKey("location_interval_seconds")
        private val UPLOAD_WINDOW = longPreferencesKey("upload_window_seconds")
        private val WIFI_ONLY = booleanPreferencesKey("wifi_only_uploads")
        private val CALL_LOG_ENABLED = booleanPreferencesKey("call_log_enabled")
        private val RECORDINGS_ENABLED = booleanPreferencesKey("recordings_enabled")
        private val MANAGED = booleanPreferencesKey("managed_by_mdm")
        private val MAX_PAYLOAD = longPreferencesKey("max_payload_bytes")
        private val LAST_UPLOAD_AT = longPreferencesKey("last_upload_at")
        private val LAST_ATTEMPT_AT = longPreferencesKey("last_attempt_at")
        private val LAST_UPLOAD_ERROR = stringPreferencesKey("last_upload_error")
        private val LAST_BUILD = longPreferencesKey("last_build")
        private val CALL_LOG_CURSOR = longPreferencesKey("call_log_cursor")
        private val RECORDING_CURSOR = longPreferencesKey("recording_cursor")
    }
}

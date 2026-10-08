package com.odoocompanion.config

import android.content.Context
import android.security.NetworkSecurityPolicy
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

data class Settings(
    val baseUrl: String = "",
    val identifier: String = "",
    val token: String = "",
    val locationIntervalSeconds: Long = DEFAULT_LOCATION_INTERVAL_SECONDS,
    val uploadWindowSeconds: Long = DEFAULT_UPLOAD_WINDOW_SECONDS,
    val minMoveMetres: Long = DEFAULT_MIN_MOVE_METRES,
    val wifiOnlyUploads: Boolean = false,
    val callLogEnabled: Boolean = true,
    val recordingsEnabled: Boolean = false,
    val managed: Boolean = false,
    // Which fields the policy is actually managing. Being managed is one
    // question -- is there a policy at all -- and which fields it governs is
    // another, and the form needs the second one.
    val managedKeys: Set<String> = emptySet(),
    val maxPayloadBytes: Long = 0,
    val maxPayloadLearnedAt: Long = 0,
    val serverNamesItself: Boolean = false,
    val lastUploadAt: Long = 0,
    val lastAttemptAt: Long = 0,
    val lastUploadError: String? = null,
) {
    val isEnrolled: Boolean
        get() = baseUrl.isNotBlank() && identifier.isNotBlank() && token.isNotBlank()

    val governedKeys: Set<String>
        get() = if (managed) managedKeys else emptySet()

    override fun toString(): String =
        "Settings(baseUrl=$baseUrl, identifier=$identifier, token=${token.redacted()}, " +
            "locationIntervalSeconds=$locationIntervalSeconds, " +
            "uploadWindowSeconds=$uploadWindowSeconds, minMoveMetres=$minMoveMetres, " +
            "wifiOnlyUploads=$wifiOnlyUploads, " +
            "callLogEnabled=$callLogEnabled, recordingsEnabled=$recordingsEnabled, " +
            "managed=$managed, managedKeys=$managedKeys, maxPayloadBytes=$maxPayloadBytes, " +
            "maxPayloadLearnedAt=$maxPayloadLearnedAt, " +
            "serverNamesItself=$serverNamesItself, " +
            "lastUploadAt=$lastUploadAt, lastAttemptAt=$lastAttemptAt, " +
            "lastUploadError=$lastUploadError)"

    fun payloadLimit(now: Long): Long =
        if (maxPayloadBytes > 0 && now - maxPayloadLearnedAt < PAYLOAD_LIMIT_TTL_MILLIS) {
            maxPayloadBytes
        } else {
            0
        }

    fun endpoint(suffix: String): String = baseUrl.toHttpUrl()
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

const val PAYLOAD_LIMIT_TTL_MILLIS = 24L * 60 * 60 * 1000

const val MIN_LOCATION_INTERVAL_SECONDS = 15L
const val MAX_LOCATION_INTERVAL_SECONDS = 24L * 60 * 60

// Zero keeps every fix, which is what this app has always done. It is the
// default because how finely a fleet is tracked is a decision for whoever runs
// the fleet, not something a release should change under them.
const val DEFAULT_MIN_MOVE_METRES = 0L

// Past this a threshold stops filtering noise and starts dropping journeys: a
// car covers it in a couple of seconds.
const val MAX_MIN_MOVE_METRES = 500L

const val DEFAULT_UPLOAD_WINDOW_SECONDS = 180L
const val MAX_UPLOAD_WINDOW_SECONDS = 3600L
const val MIN_UPLOAD_WINDOW_SECONDS = 0L

data class FormValues(
    val baseUrl: String,
    val identifier: String,
    val token: String,
    val callLogEnabled: Boolean,
    val recordingsEnabled: Boolean,
    val wifiOnlyUploads: Boolean,
    val locationIntervalSeconds: Long?,
    val uploadWindowSeconds: Long?,
)

sealed interface EnrollmentResult {
    data object Saved : EnrollmentResult
    data object InvalidBaseUrl : EnrollmentResult
    data object InvalidIdentifier : EnrollmentResult
    data object CleartextRefused : EnrollmentResult
}

// Whether this build would refuse to send to the URL: http, to a host the
// network security policy does not open. Asked per host, as OkHttp asks it, so a
// host a network_security_config.xml permits is not refused here and then sent
// to anyway, nor the reverse.
fun refusesCleartext(value: String, cleartextPermitted: (host: String) -> Boolean): Boolean =
    value.trim().toHttpUrlOrNull()?.let { !it.isHttps && !cleartextPermitted(it.host) } ?: false

fun isUsableBaseUrl(value: String): Boolean = value.trim().toHttpUrlOrNull() != null

fun isUsableIdentifier(value: String): Boolean =
    value.trim().let { it.isNotEmpty() && IDENTIFIER_PATTERN.matches(it) }

// The characters an identifier may hold, one at a time: the form drops any
// other as it is typed or pasted instead of refusing the whole value on Save.
fun isIdentifierChar(c: Char): Boolean =
    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in IDENTIFIER_PUNCTUATION

private const val IDENTIFIER_PUNCTUATION = "._~-"

private val IDENTIFIER_PATTERN = Regex("[A-Za-z0-9._~-]+")

class DeviceConfig(
    private val store: DataStore<Preferences>,
    private val cleartextPermitted: (host: String) -> Boolean = { true },
) {
    constructor(context: Context) : this(storeFor(context), ::cleartextPermittedFor)

    val settings: Flow<Settings> = store.data.map { it.toSettings() }

    private fun Preferences.toSettings() = Settings(
        baseUrl = this[BASE_URL].orEmpty(),
        identifier = this[IDENTIFIER].orEmpty(),
        token = this[TOKEN].orEmpty(),
        locationIntervalSeconds = this[LOCATION_INTERVAL] ?: DEFAULT_LOCATION_INTERVAL_SECONDS,
        uploadWindowSeconds = this[UPLOAD_WINDOW] ?: DEFAULT_UPLOAD_WINDOW_SECONDS,
        minMoveMetres = this[MIN_MOVE] ?: DEFAULT_MIN_MOVE_METRES,
        wifiOnlyUploads = this[WIFI_ONLY] ?: false,
        callLogEnabled = this[CALL_LOG_ENABLED] ?: true,
        recordingsEnabled = this[RECORDINGS_ENABLED] ?: false,
        managed = this[MANAGED] ?: false,
        managedKeys = this[MANAGED_KEYS] ?: emptySet(),
        maxPayloadBytes = this[MAX_PAYLOAD] ?: 0,
        maxPayloadLearnedAt = this[MAX_PAYLOAD_LEARNED_AT] ?: 0,
        serverNamesItself = this[SERVER_NAMES_ITSELF] ?: false,
        lastUploadAt = this[LAST_UPLOAD_AT] ?: 0,
        lastAttemptAt = this[LAST_ATTEMPT_AT] ?: 0,
        lastUploadError = this[LAST_UPLOAD_ERROR],
    )

    suspend fun current(): Settings = settings.first()

    // For a reader in the background, where a throw is worse than an answer:
    // out of a worker it is a failure that cancels the upload chained behind
    // it, and out of the location service's coroutines it kills the process.
    // A corrupt file is already replaced by storeFor; this is the disk itself.
    suspend fun currentOrNull(): Settings? = try {
        current()
    } catch (unreadable: IOException) {
        Log.w(TAG, "Cannot read the device configuration", unreadable)
        null
    }

    // The form as one write: validated before anything is stored, and applied
    // in a single edit so a refused enrolment leaves the switches alone too.
    // A field the policy governs is skipped -- the stored value is the
    // policy's, whatever the disabled view happened to show.
    // Validated against the same snapshot it writes to: read before the edit,
    // a policy withdrawn in between let a base URL nobody had checked through.
    suspend fun saveForm(form: FormValues): EnrollmentResult {
        var result: EnrollmentResult = EnrollmentResult.Saved
        store.edit { prefs ->
            val governed = prefs.toSettings().governedKeys
            val baseUrl = form.baseUrl.trim().takeIf { BASE_URL.name !in governed }
            val identifier = form.identifier.trim().takeIf { IDENTIFIER.name !in governed }
            enrollmentProblem(baseUrl, identifier)?.let { refused ->
                result = refused
                return@edit
            }
            fun <T> put(key: Preferences.Key<T>, value: T?) {
                if (value != null && key.name !in governed) prefs[key] = value
            }
            prefs.pointAt(baseUrl, identifier)
            put(TOKEN, form.token.trim())
            put(CALL_LOG_ENABLED, form.callLogEnabled)
            put(RECORDINGS_ENABLED, form.recordingsEnabled)
            put(WIFI_ONLY, form.wifiOnlyUploads)
            put(
                LOCATION_INTERVAL,
                form.locationIntervalSeconds
                    ?.coerceIn(MIN_LOCATION_INTERVAL_SECONDS, MAX_LOCATION_INTERVAL_SECONDS),
            )
            put(
                UPLOAD_WINDOW,
                form.uploadWindowSeconds
                    ?.coerceIn(MIN_UPLOAD_WINDOW_SECONDS, MAX_UPLOAD_WINDOW_SECONDS),
            )
        }
        return result
    }

    // A null field is not the form's to validate: the policy governs it.
    private fun enrollmentProblem(baseUrl: String?, identifier: String?): EnrollmentResult? = when {
        baseUrl != null && !isUsableBaseUrl(baseUrl) -> EnrollmentResult.InvalidBaseUrl

        baseUrl != null && refusesCleartext(baseUrl, cleartextPermitted) ->
            EnrollmentResult.CleartextRefused

        identifier != null && !isUsableIdentifier(identifier) ->
            EnrollmentResult.InvalidIdentifier

        else -> null
    }

    // What was learned from a server belongs to what it was learned from:
    // whether it names itself is a fact about the server, and its payload cap
    // is a setting on the device record. A rotated token keeps both.
    private fun MutablePreferences.pointAt(baseUrl: String?, identifier: String?) {
        val newServer = baseUrl != null && this[BASE_URL] != baseUrl
        val newDevice = newServer || (identifier != null && this[IDENTIFIER] != identifier)
        if (newServer) remove(SERVER_NAMES_ITSELF)
        if (newDevice) {
            remove(MAX_PAYLOAD)
            remove(MAX_PAYLOAD_LEARNED_AT)
        }
        baseUrl?.let { this[BASE_URL] = it }
        identifier?.let { this[IDENTIFIER] = it }
    }

    // A base URL this build cannot reach is refused here as the form refuses
    // it, and like any other unusable managed value it leaves the stored one
    // alone: the key stays governed, the enrolment stays working.
    suspend fun applyManaged(values: ManagedValues): Boolean {
        val baseUrl = values.baseUrl?.takeUnless { refusesCleartext(it, cleartextPermitted) }
        var changed = false
        store.edit { prefs ->
            val before = prefs.toSettings().managedFacet()

            prefs[MANAGED] = values.policyPresent
            prefs[MANAGED_KEYS] = values.presentKeys
            prefs.pointAt(baseUrl, values.identifier)
            values.token?.let { prefs[TOKEN] = it }
            values.callLogEnabled?.let { prefs[CALL_LOG_ENABLED] = it }
            values.recordingsEnabled?.let { prefs[RECORDINGS_ENABLED] = it }
            values.wifiOnlyUploads?.let { prefs[WIFI_ONLY] = it }

            values.uploadWindowSeconds?.let { prefs[UPLOAD_WINDOW] = it }
            values.minMoveMetres?.let { prefs[MIN_MOVE] = it }
            values.locationIntervalSeconds?.let { prefs[LOCATION_INTERVAL] = it }
            changed = prefs.toSettings().managedFacet() != before
        }
        return changed
    }

    private data class ManagedFacet(
        val managed: Boolean,
        val managedKeys: Set<String>,
        val baseUrl: String,
        val identifier: String,
        val token: String,
        val callLogEnabled: Boolean,
        val recordingsEnabled: Boolean,
        val wifiOnlyUploads: Boolean,
        val uploadWindowSeconds: Long,
        val locationIntervalSeconds: Long,
        val minMoveMetres: Long,
    )

    private fun Settings.managedFacet() = ManagedFacet(
        managed = managed,
        managedKeys = managedKeys,
        baseUrl = baseUrl,
        identifier = identifier,
        token = token,
        callLogEnabled = callLogEnabled,
        recordingsEnabled = recordingsEnabled,
        wifiOnlyUploads = wifiOnlyUploads,
        uploadWindowSeconds = uploadWindowSeconds,
        locationIntervalSeconds = locationIntervalSeconds,
        minMoveMetres = minMoveMetres,
    )

    internal suspend fun clearEnrollment() {
        store.edit { prefs ->
            prefs.remove(BASE_URL)
            prefs.remove(IDENTIFIER)
            prefs.remove(TOKEN)
        }
    }

    // Test seams: switches on a phone the form could not save them for, because
    // it is not enrolled. Production writes go through saveForm or applyManaged.
    @VisibleForTesting
    internal suspend fun setFeature(callLog: Boolean? = null, recordings: Boolean? = null) {
        store.edit { prefs ->
            callLog?.let { prefs[CALL_LOG_ENABLED] = it }
            recordings?.let { prefs[RECORDINGS_ENABLED] = it }
        }
    }

    @VisibleForTesting
    internal suspend fun setWifiOnlyUploads(enabled: Boolean) {
        store.edit { prefs -> prefs[WIFI_ONLY] = enabled }
    }

    suspend fun consumeBuildChange(version: Long): Boolean {
        var changed = false
        store.edit { prefs ->
            changed = prefs[LAST_BUILD] != version
            if (changed) prefs[LAST_BUILD] = version
        }
        return changed
    }

    suspend fun learnServerNamesItself() {
        store.edit { prefs -> prefs[SERVER_NAMES_ITSELF] = true }
    }

    suspend fun learnPayloadLimit(bytes: Long, at: Long) {
        store.edit { prefs ->
            if (bytes > 0) {
                prefs[MAX_PAYLOAD] = bytes
                prefs[MAX_PAYLOAD_LEARNED_AT] = at
            } else {
                prefs.remove(MAX_PAYLOAD)
                prefs.remove(MAX_PAYLOAD_LEARNED_AT)
            }
        }
    }

    suspend fun recordUpload(at: Long, delivered: Boolean, error: String?) {
        store.edit { prefs ->
            prefs[LAST_ATTEMPT_AT] = at
            if (delivered) prefs[LAST_UPLOAD_AT] = at
            if (error != null) prefs[LAST_UPLOAD_ERROR] = error else prefs.remove(LAST_UPLOAD_ERROR)
        }
    }

    suspend fun callLogCursor(): Long = store.data.first()[CALL_LOG_CURSOR] ?: 0L

    // Null until an id cursor has been written. The timestamp cursor's key is
    // left alone rather than reinterpreted: its value is an epoch in the
    // billions, and read as an id it would match no row ever again.
    suspend fun callLogIdCursor(): Long? = store.data.first()[CALL_LOG_ID_CURSOR]

    suspend fun setCallLogIdCursor(value: Long) {
        store.edit { prefs -> prefs[CALL_LOG_ID_CURSOR] = value }
    }

    // Absent is not the same as zero here: absent is what a handset upgrading
    // from a build that cursored on a timestamp looks like.
    internal suspend fun clearCallLogIdCursor() {
        store.edit { prefs -> prefs.remove(CALL_LOG_ID_CURSOR) }
    }

    // What a build that cursored on a timestamp left behind; only a test
    // writes it now, to stand in for such a build.
    @VisibleForTesting
    internal suspend fun setCallLogCursor(value: Long) {
        store.edit { prefs -> prefs[CALL_LOG_CURSOR] = value }
    }

    companion object {
        internal const val STORE_NAME = "companion_config"

        private const val TAG = "DeviceConfig"

        private fun cleartextPermittedFor(host: String): Boolean =
            NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)

        internal fun storeFor(context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            ) { context.preferencesDataStoreFile(STORE_NAME) }

        private val BASE_URL = stringPreferencesKey(ManagedKey.BASE_URL)
        private val IDENTIFIER = stringPreferencesKey(ManagedKey.IDENTIFIER)
        private val TOKEN = stringPreferencesKey(ManagedKey.TOKEN)
        private val LOCATION_INTERVAL = longPreferencesKey(ManagedKey.LOCATION_INTERVAL_SECONDS)
        private val UPLOAD_WINDOW = longPreferencesKey(ManagedKey.UPLOAD_WINDOW_SECONDS)
        private val MIN_MOVE = longPreferencesKey(ManagedKey.MIN_MOVE_METRES)
        private val WIFI_ONLY = booleanPreferencesKey(ManagedKey.WIFI_ONLY_UPLOADS)
        private val CALL_LOG_ENABLED = booleanPreferencesKey(ManagedKey.CALL_LOG_ENABLED)
        private val RECORDINGS_ENABLED = booleanPreferencesKey(ManagedKey.RECORDINGS_ENABLED)
        private val MANAGED = booleanPreferencesKey("managed_by_mdm")
        private val MANAGED_KEYS = stringSetPreferencesKey("managed_keys")
        private val MAX_PAYLOAD = longPreferencesKey("max_payload_bytes")
        private val MAX_PAYLOAD_LEARNED_AT = longPreferencesKey("max_payload_learned_at")
        private val SERVER_NAMES_ITSELF = booleanPreferencesKey("server_names_itself")
        private val LAST_UPLOAD_AT = longPreferencesKey("last_upload_at")
        private val LAST_ATTEMPT_AT = longPreferencesKey("last_attempt_at")
        private val LAST_UPLOAD_ERROR = stringPreferencesKey("last_upload_error")
        private val LAST_BUILD = longPreferencesKey("last_build")
        private val CALL_LOG_CURSOR = longPreferencesKey("call_log_cursor")
        private val CALL_LOG_ID_CURSOR = longPreferencesKey("call_log_id_cursor")
    }
}

package com.odoocompanion.net

import android.util.Log
import com.odoocompanion.config.Settings
import com.odoocompanion.push.PushKeyLookup
import com.odoocompanion.push.PushRegistration
import com.odoocompanion.softphone.SoftphoneLookup
import com.odoocompanion.softphone.SoftphoneSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

sealed interface UploadOutcome {
    data class Success(
        val accepted: Int,
        val duplicates: Int,
        val skipped: Int,
        val skippedIndexes: List<Int> = emptyList(),
        val limitBytes: Long? = null,
    ) : UploadOutcome

    data class Rejected(val code: Int) : UploadOutcome

    data object Duplicate : UploadOutcome

    data class Retry(
        val reason: String,
        val serverFault: Boolean = false,
        val linkDown: Boolean = false,
    ) : UploadOutcome

    data class TooLarge(val limitBytes: Long?) : UploadOutcome
}

class OdooClient(
    private val http: OkHttpClient = defaultClient(),
    private val onServerNamedItself: suspend () -> Unit = {},
) {
    suspend fun post(settings: Settings, suffix: String, body: String): UploadOutcome =
        post(settings, suffix, body.toRequestBody(JSON))

    suspend fun post(
        settings: Settings,
        suffix: String,
        file: File,
        metadata: JsonObject,
        fileKey: String = FileBody.AUDIO_KEY,
    ): UploadOutcome = post(settings, suffix, FileBody(metadata, file, JSON, fileKey))

    fun wireSize(file: File, metadata: JsonObject, fileKey: String = FileBody.AUDIO_KEY,): Long =
        FileBody(metadata, file, JSON, fileKey).contentLength()

    private suspend fun post(
        settings: Settings,
        suffix: String,
        body: RequestBody,
    ): UploadOutcome = exchange(
        settings,
        suffix,
        body,
        failed = { reason, cause ->
            UploadOutcome.Retry(reason, linkDown = cause !is InterruptedIOException)
        },
    ) { code, text, reply ->
        if (!settings.serverNamesItself && reply.namesTheService()) onServerNamedItself()
        classify(code, text, reply, settings.serverNamesItself)
    }

    suspend fun softphone(settings: Settings): SoftphoneLookup = exchange(
        settings,
        SOFTPHONE,
        body = null,
        failed = { reason, _ -> SoftphoneLookup.Unavailable(reason) },
    ) { code, text, reply ->
        when {
            !reply.namesTheService() -> SoftphoneLookup.Unavailable(notOdoo(code, text))

            code in 200..299 ->
                SoftphoneSettings.fromReply(reply)?.let { SoftphoneLookup.Found(it) }
                    ?: SoftphoneLookup.Unavailable("unusable softphone settings")

            code == HTTP_NOT_FOUND && reply.errorIs(NO_EXTENSION) -> SoftphoneLookup.NoExtension

            code in AUTH_REFUSED -> SoftphoneLookup.Refused(code)

            else -> SoftphoneLookup.Unavailable(reasonFor(code, text))
        }
    }

    suspend fun pushKey(settings: Settings): PushKeyLookup = push(settings, null)

    suspend fun registerPush(settings: Settings, registration: PushRegistration?): PushKeyLookup {
        val body = buildJsonObject {
            if (registration == null) {
                put("endpoint", JsonNull)
            } else {
                put("endpoint", registration.endpoint)
                putJsonObject("keys") {
                    put("p256dh", registration.p256dh)
                    put("auth", registration.auth)
                }
            }
        }
        return push(settings, body.toString().toRequestBody(JSON))
    }

    private suspend fun push(settings: Settings, body: RequestBody?): PushKeyLookup = exchange(
        settings,
        PUSH,
        body,
        failed = { reason, _ -> PushKeyLookup.Unavailable(reason) },
    ) { code, text, reply ->
        val key = reply.primitive("vapid_public_key")?.takeIf { it.isString }?.content
        when {
            !reply.namesTheService() -> PushKeyLookup.Unavailable(notOdoo(code, text))

            code in 200..299 && key != null ->
                PushKeyLookup.Found(key, reply.primitive("registered")?.booleanOrNull == true)

            reply.errorIs(PUSH_UNAVAILABLE) -> PushKeyLookup.Off

            else -> PushKeyLookup.Unavailable(reasonFor(code, text))
        }
    }

    private suspend fun <T> exchange(
        settings: Settings,
        suffix: String,
        body: RequestBody?,
        failed: (reason: String, cause: Exception) -> T,
        read: suspend (code: Int, text: String, reply: JsonObject?) -> T,
    ): T {
        val request = try {
            Request.Builder()
                .url(settings.endpoint(suffix))
                .addHeader("Authorization", "Bearer ${settings.token}")
                .apply { if (body == null) get() else post(body) }
                .build()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Cannot build a request for $suffix", e)
            return failed(e.message ?: "malformed endpoint URL", e)
        }
        return try {
            http.newCall(request).await().use { response ->
                val text = response.peekBody(MAX_READ_BYTES).string()
                read(response.code, text, text.asJsonObject())
            }
        } catch (e: IOException) {
            Log.w(TAG, "Request to $suffix failed", e)
            failed(e.message ?: "network failure", e)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, _, _ -> response.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWith(Result.failure(e))
            }
        })
    }

    private fun classify(
        code: Int,
        text: String,
        body: JsonObject?,
        mustNameItself: Boolean,
    ): UploadOutcome {
        val counts = Counts(
            accepted = body.countAt("accepted"),
            duplicates = body.countAt("duplicates"),
            skipped = body.countAt("skipped"),
        )
        return when {
            removesRows(code) && !body.isOdooReply(mustNameItself) ->
                UploadOutcome.Retry(notOdoo(code, text), linkDown = true)

            code in 200..299 && !body.reportsSuccess() ->
                UploadOutcome.Retry(notOdoo(code, text), linkDown = true)

            code in 200..299 -> UploadOutcome.Success(
                counts.accepted,
                counts.duplicates,
                counts.skipped,
                body.indexesAt("skipped_indexes"),
                body.primitive("max_payload_bytes")?.longOrNull,
            )

            code == HTTP_CONFLICT -> UploadOutcome.Duplicate

            code == HTTP_UNPROCESSABLE && body.reportsDelivery(counts) -> UploadOutcome.Duplicate

            body.errorIs(DAMAGED_BODY) -> UploadOutcome.Retry(
                "the server could not parse the request body, which left this phone as JSON" +
                    " -- it was damaged on the way, so the queue is kept",
            )

            code in PERMANENTLY_REFUSED -> UploadOutcome.Rejected(code)

            code == HTTP_TOO_LARGE -> UploadOutcome.TooLarge(declaredLimit(text, body))

            else -> UploadOutcome.Retry(
                reasonFor(code, text),
                serverFault = code in ROW_FAULTS,
                linkDown = code == HTTP_UNAVAILABLE ||
                    (code in DEVICE_REFUSALS && body.isOdooReply(mustNameItself)),
            )
        }
    }

    private fun declaredLimit(text: String, body: JsonObject?): Long? =
        body.primitive("limit_bytes")?.longOrNull
            ?: DECLARED_KILOBYTES.find(text)?.groupValues?.get(1)?.toLongOrNull()?.times(1024)

    private fun removesRows(code: Int): Boolean =
        code in 200..299 || code == HTTP_CONFLICT || code in PERMANENTLY_REFUSED

    private fun notOdoo(code: Int, text: String): String =
        "$code from something that is not the Odoo endpoint${excerpt(text)} - a captive " +
            "portal or proxy is answering, so the queue is kept"

    private data class Counts(val accepted: Int, val duplicates: Int, val skipped: Int)

    private fun JsonObject?.countAt(key: String): Int = primitive(key)?.intOrNull ?: 0

    private fun JsonObject?.isOdooReply(mustNameItself: Boolean): Boolean = when {
        this == null -> false
        mustNameItself -> namesTheService()
        else -> "status" in this || "error" in this
    }

    private fun JsonObject?.namesTheService(): Boolean = textAt("service") == SERVICE

    private fun JsonObject?.errorIs(code: String): Boolean = textAt("error") == code

    private fun JsonObject?.reportsSuccess(): Boolean = textAt("status") == "success"

    private fun JsonObject?.reportsDelivery(counts: Counts): Boolean =
        reportsSuccess() && counts.duplicates > 0 && counts.skipped == 0

    private fun JsonObject?.textAt(key: String): String? = primitive(key)?.contentOrNull

    private fun JsonObject?.indexesAt(key: String): List<Int> =
        (this?.get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty()

    private fun reasonFor(code: Int, text: String): String = when (code) {
        401, 403 -> "auth rejected ($code)"

        404 ->
            "device not found on the server (404) — check the identifier is " +
                "correct and the device is not archived"

        429 -> "rate limited"

        in 500..599 -> "server $code"

        else -> "unexpected response $code${excerpt(text)}"
    }

    private fun excerpt(text: String): String =
        text.take(MAX_EXCERPT_CHARS).split(WHITESPACE).filter { it.isNotEmpty() }
            .joinToString(" ")
            .let { if (it.isEmpty()) "" else ": $it" }

    private companion object {
        const val TAG = "OdooClient"
        const val HTTP_CONFLICT = 409
        const val SERVICE = "remote_mobile"
        const val HTTP_TOO_LARGE = 413
        val DECLARED_KILOBYTES = Regex("""maximum size of (\d+)KB""")
        const val MAX_READ_BYTES = 64L * 1024
        const val MAX_EXCERPT_CHARS = 120
        val WHITESPACE = Regex("""\s+""")
        const val HTTP_UNPROCESSABLE = 422
        const val HTTP_NOT_FOUND = 404
        const val SOFTPHONE = "softphone"
        const val NO_EXTENSION = "no_extension"
        const val PUSH = "push"
        const val PUSH_UNAVAILABLE = "push_unavailable"
        val AUTH_REFUSED = setOf(401, 403)

        const val DAMAGED_BODY = "invalid_json"
        val JSON = "application/json; charset=utf-8".toMediaType()

        val PERMANENTLY_REFUSED = setOf(400, HTTP_UNPROCESSABLE)

        val ROW_FAULTS = (500..599) - setOf(502, 503, 504)

        val DEVICE_REFUSALS = setOf(401, 403, 404, 429)
        const val HTTP_UNAVAILABLE = 503

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

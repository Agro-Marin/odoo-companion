package com.odoocompanion.net

import android.util.Log
import com.odoocompanion.config.Settings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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

    data class Retry(val reason: String, val serverFault: Boolean = false) : UploadOutcome

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
    ): UploadOutcome = post(settings, suffix, RecordingBody(metadata, file, JSON))

    fun recordingWireSize(file: File, metadata: JsonObject): Long =
        RecordingBody(metadata, file, JSON).contentLength()

    private suspend fun post(
        settings: Settings,
        suffix: String,
        body: RequestBody,
    ): UploadOutcome = try {
        val request = Request.Builder()
            .url(settings.endpoint(suffix))
            .addHeader("Authorization", "Bearer ${settings.token}")
            .post(body)
            .build()
        http.newCall(request).await().use { response ->
            val text = response.peekBody(MAX_READ_BYTES).string()
            val body = text.asJsonObject()
            if (!settings.serverNamesItself && body.namesTheService()) onServerNamedItself()
            classify(response.code, text, body, settings.serverNamesItself)
        }
    } catch (e: IOException) {
        Log.w(TAG, "Upload to $suffix failed", e)
        UploadOutcome.Retry(e.message ?: "network failure")
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Cannot build a request for $suffix", e)
        UploadOutcome.Retry(e.message ?: "malformed endpoint URL")
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
            accepted = body.intAt("accepted"),
            duplicates = body.intAt("duplicates"),
            skipped = body.intAt("skipped"),
        )
        return when {
            removesRows(code) && !body.isOdooReply(mustNameItself) ->
                UploadOutcome.Retry(notOdoo(code, text))

            code in 200..299 && !body.reportsSuccess() -> UploadOutcome.Retry(notOdoo(code, text))

            code in 200..299 -> UploadOutcome.Success(
                counts.accepted,
                counts.duplicates,
                counts.skipped,
                body.indexesAt("skipped_indexes"),
                body?.get("max_payload_bytes")?.jsonPrimitive?.longOrNull,
            )

            code == HTTP_CONFLICT -> UploadOutcome.Duplicate

            code == HTTP_UNPROCESSABLE && body.reportsDelivery(counts) -> UploadOutcome.Duplicate

            code in PERMANENTLY_REFUSED -> UploadOutcome.Rejected(code)

            code == HTTP_TOO_LARGE -> UploadOutcome.TooLarge(declaredLimit(text, body))

            else -> UploadOutcome.Retry(reasonFor(code, text), serverFault = code in ROW_FAULTS)
        }
    }

    private fun declaredLimit(text: String, body: JsonObject?): Long? =
        body?.get("limit_bytes")?.jsonPrimitive?.longOrNull
            ?: DECLARED_KILOBYTES.find(text)?.groupValues?.get(1)?.toLongOrNull()?.times(1024)

    private fun removesRows(code: Int): Boolean =
        code in 200..299 || code == HTTP_CONFLICT || code in PERMANENTLY_REFUSED

    private fun notOdoo(code: Int, text: String): String =
        "$code from something that is not the Odoo endpoint${excerpt(text)} - a captive " +
            "portal or proxy is answering, so the queue is kept"

    private data class Counts(val accepted: Int, val duplicates: Int, val skipped: Int)

    private fun JsonObject?.isOdooReply(mustNameItself: Boolean): Boolean = when {
        this == null -> false
        mustNameItself -> namesTheService()
        else -> "status" in this || "error" in this
    }

    private fun JsonObject?.namesTheService(): Boolean =
        this?.get("service")?.jsonPrimitive?.contentOrNull == SERVICE

    private fun JsonObject?.reportsSuccess(): Boolean =
        this?.get("status")?.jsonPrimitive?.contentOrNull == "success"

    private fun JsonObject?.reportsDelivery(counts: Counts): Boolean =
        reportsSuccess() && counts.duplicates > 0 && counts.skipped == 0

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

    private fun String.asJsonObject(): JsonObject? = runCatching {
        Json.parseToJsonElement(this).jsonObject
    }.getOrNull()

    private fun JsonObject?.intAt(key: String): Int = this?.get(key)?.jsonPrimitive?.intOrNull ?: 0

    private fun JsonObject?.indexesAt(key: String): List<Int> =
        (this?.get(key) as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull }.orEmpty()

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
        val JSON = "application/json; charset=utf-8".toMediaType()

        val PERMANENTLY_REFUSED = setOf(400, HTTP_UNPROCESSABLE)

        val ROW_FAULTS = (500..599) - setOf(502, 503, 504)

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

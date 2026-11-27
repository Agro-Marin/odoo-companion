package com.odoocompanion.sync

import android.util.Log
import com.odoocompanion.config.Settings
import com.odoocompanion.data.CURRENT_PAYLOAD_VERSION
import com.odoocompanion.data.DeadReason
import com.odoocompanion.data.OutboxDao
import com.odoocompanion.data.OutboxEntry
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.net.FileBody
import com.odoocompanion.net.OdooClient
import com.odoocompanion.net.UploadOutcome
import com.odoocompanion.sync.OutboxDrainer.Outcome
import com.odoocompanion.system.debug
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal class UploadSession(
    val dao: OutboxDao,
    private val uploads: OdooClient.Uploads,
    val tally: DrainTally,
    val now: () -> Long,
    private val withCurrentCredentials: suspend (Settings, suspend () -> Unit) -> Boolean,
    private val persistLimit: suspend (Long) -> Boolean,
) {
    suspend fun take(kind: String, limit: Int,): List<OutboxEntry>? {
        lateinit var rows: List<OutboxEntry>
        val confirmed = withCurrentCredentials(uploads.settings) {
            rows = dao.take(kind, limit, tally.readyAt, uploads.settings.enrollment)
        }
        if (confirmed) return rows

        credentialsUnavailable()
        return null
    }

    fun credentialsUnavailable(): Outcome {
        tally.lastError = "could not confirm the current enrolment"
        tally.stopAfterKind = true
        debug(DRAIN_TAG) { "Stopping this upload pass: ${tally.lastError}" }
        return Outcome.RETRY
    }

    suspend fun post(suffix: String, body: String): UploadOutcome =
        withCredentials { uploads.post(suffix, body) }

    suspend fun post(suffix: String, body: FileBody): UploadOutcome = withCredentials {
        tally.bytesLeft -= body.contentLength()
        uploads.post(suffix, body)
    }

    private suspend fun withCredentials(send: suspend () -> UploadOutcome): UploadOutcome =
        coroutineScope {
            lateinit var request: Deferred<UploadOutcome>
            val confirmed = withCurrentCredentials(uploads.settings) {
                request = async(start = CoroutineStart.UNDISPATCHED) { send() }
            }
            if (!confirmed) return@coroutineScope UploadOutcome.CredentialsUnavailable
            tally.attempted = true
            request.await()
        }

    suspend fun learn(limitBytes: Long?) {
        if (limitBytes == null || limitBytes <= 0) return
        tally.payloadLimit = limitBytes
        if (tally.persistedLimit == limitBytes) return
        if (persistLimit(limitBytes)) tally.persistedLimit = limitBytes
    }

    suspend fun abandon(
        kind: String,
        ids: List<Long>,
        reason: String,
        counter: MutableMap<String, Int> = tally.skipped,
        deadReason: String = DeadReason.REFUSED,
    ) {
        if (ids.isEmpty()) return
        tally.note(counter, kind, ids.size)

        Log.w(DRAIN_TAG, "Setting aside ${ids.size} $kind row(s): $reason")
        if (kind in OutboxKind.IRREPLACEABLE) {
            dao.markDeadIds(ids, now(), reason, deadReason)
            tally.deadLettered += ids.size
        } else {
            dao.delete(ids)
            tally.discarded += ids.size
        }
    }

    suspend fun deferFile(id: Long, reason: String) {
        Log.w(DRAIN_TAG, "Deferring file row $id: $reason")
        dao.deferFile(id, reason, deferral(0, tally.readyAt))
        tally.lastError = reason
    }

    suspend fun deferRuledOn(entry: OutboxEntry, reason: String) {
        Log.w(DRAIN_TAG, "Keeping ${entry.kind} row ${entry.id} the server would not take: $reason")
        dao.markFailed(
            listOf(entry.id),
            reason,
            serverFault = true,
            retryAfter = deferral(entry.attempts, tally.readyAt),
        )
        tally.lastError = reason
    }

    suspend fun failBatch(ids: List<Long>, cause: Throwable): Outcome =
        chargeTheLink(ids, "upload failed (${cause.javaClass.simpleName})", linkDown = false)

    suspend fun chargeTheLink(ids: List<Long>, reason: String, linkDown: Boolean,): Outcome {
        dao.markFailed(ids, reason)
        tally.lastError = reason
        tally.stopAfterKind = linkDown
        return Outcome.RETRY
    }

    private fun deferral(attemptsSoFar: Int, readyAt: Long): Long {
        val step = minOf(attemptsSoFar, ROW_BACKOFF_DOUBLINGS)
        return maxOf(readyAt, now()) +
            minOf(
                OutboxDrainer.ROW_BACKOFF_MAX_MILLIS,
                OutboxDrainer.ROW_BACKOFF_BASE_MILLIS shl step
            )
    }

    private companion object {
        private const val ROW_BACKOFF_DOUBLINGS = 6
    }
}

internal fun undecodableReason(entries: List<OutboxEntry>): String {
    val versions = entries.map { it.payloadVersion }.distinct().sorted()
    val written = versions.joinToString(", ")
    return if (versions == listOf(CURRENT_PAYLOAD_VERSION)) {
        "payload could not be decoded by this build, which wrote it"
    } else {
        "payload was written in queue format $written and this build reads " +
            "$CURRENT_PAYLOAD_VERSION"
    }
}

internal fun UploadOutcome.diagnostic(): String = when (this) {
    is UploadOutcome.Retry -> "Retry($scope)"
    else -> toString()
}

internal fun UploadOutcome.TooLarge.describe(): String =
    limitBytes?.let { "over the server's ${it / 1024} KB payload cap" }
        ?: "over the server's payload cap"

@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (stopped: CancellationException) {
    throw stopped
} catch (failure: Exception) {
    Result.failure(failure)
}

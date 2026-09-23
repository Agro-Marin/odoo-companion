package com.odoocompanion.sync

import android.util.Log
import com.odoocompanion.config.Settings
import com.odoocompanion.data.CURRENT_PAYLOAD_VERSION
import com.odoocompanion.data.DeadReason
import com.odoocompanion.data.OutboxDao
import com.odoocompanion.data.OutboxEntry
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.data.OutboxLimits
import com.odoocompanion.net.CallLogUpload
import com.odoocompanion.net.CallRecord
import com.odoocompanion.net.LocationFix
import com.odoocompanion.net.LocationUpload
import com.odoocompanion.net.OdooClient
import com.odoocompanion.net.RecordingMetadata
import com.odoocompanion.net.UploadOutcome
import com.odoocompanion.net.WireJson
import com.odoocompanion.system.debug
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File

data class DrainReport(
    val outcome: OutboxDrainer.Outcome,
    val lastError: String? = null,
    val accepted: Map<String, Int> = emptyMap(),
    val skipped: Map<String, Int> = emptyMap(),
    val duplicates: Map<String, Int> = emptyMap(),
    val undecodable: Map<String, Int> = emptyMap(),
    val deadLettered: Int = 0,
    val unreachable: Int = 0,
    val discarded: Int = 0,
    val lost: Int = 0,
    val purged: Int = 0,
    val revived: Int = 0,
    val delivered: Boolean = false,
    val deferred: Boolean = false,
    val moreWorkPending: Boolean = false,
)

private class DrainTally {
    val accepted = mutableMapOf<String, Int>()
    val skipped = mutableMapOf<String, Int>()
    val duplicates = mutableMapOf<String, Int>()
    val undecodable = mutableMapOf<String, Int>()
    var lastError: String? = null

    var delivered = false

    var attempted = false
    var deadLettered = 0
    var unreachable = 0
    var discarded = 0
    var lost = 0
    var purged = 0
    var revived = 0
    var moreWork = false

    var payloadLimit = 0L
    var persistedLimit: Long? = null

    fun note(counter: MutableMap<String, Int>, kind: String, count: Int) {
        counter[kind] = (counter[kind] ?: 0) + count
    }

    fun report(outcome: OutboxDrainer.Outcome) = DrainReport(
        outcome = outcome,
        lastError = lastError,
        accepted = accepted.toMap(),
        skipped = skipped.toMap(),
        duplicates = duplicates.toMap(),
        undecodable = undecodable.toMap(),
        deadLettered = deadLettered,
        unreachable = unreachable,
        discarded = discarded,
        lost = lost,
        purged = purged,
        revived = revived,
        delivered = delivered,
        moreWorkPending = moreWork,
    )
}

class OutboxDrainer(
    private val dao: OutboxDao,
    private val client: OdooClient,
    private val now: () -> Long = System::currentTimeMillis,
    private val learnPayloadLimit: suspend (bytes: Long, at: Long) -> Unit = { _, _ -> },
    private val settingsProvider: suspend () -> Settings,
) {
    enum class Outcome { DONE, RETRY }

    suspend fun drainAll(): DrainReport {
        if (!drainLock.tryLock()) {
            Log.i(TAG, "Another drain holds the queue; leaving this pass to it")
            return DrainReport(outcome = Outcome.DONE, deferred = true)
        }
        return try {
            drain()
        } finally {
            drainLock.unlock()
        }
    }

    private suspend fun drain(): DrainReport {
        val tally = DrainTally()

        retireWhatWillNeverBeTaken(tally)
        tally.discarded = dao.deleteExhausted(OutboxKind.IRREPLACEABLE, MAX_ATTEMPTS)
        purgeExpiredDeadRows(tally)

        val settings = attempt { settingsProvider() }.getOrElse { cause ->
            return reportUnreadableSettings(tally, cause)
        }
        tally.payloadLimit = settings.payloadLimit(now())
        val results = listOf(
            drainBatch(LOCATIONS, settings, tally),
            drainBatch(CALLS, settings, tally),
            drainRecordings(settings, tally),
        )
        val outcome = if (results.any { it == Outcome.RETRY }) Outcome.RETRY else Outcome.DONE
        reviveWhatOnlyRanOutOfRetries(tally, outcome)

        if (outcome == Outcome.DONE && !tally.moreWork) {
            tally.moreWork = anythingArrivedLate(settings.uploadWindowSeconds)
        }
        return tally.report(outcome).also { debug(TAG) { "drain finished: $it" } }
    }

    private suspend fun anythingArrivedLate(windowSeconds: Long): Boolean {
        val now = now()
        if (OutboxKind.IRREPLACEABLE.any { dao.countOf(it, now) > 0 }) return true
        val positions = dao.depthOf(OutboxKind.LOCATION, now)
        return UploadCadence.dueNow(
            queued = positions.queued,
            oldestCreatedAt = positions.oldestCreatedAt,
            windowSeconds = windowSeconds,
            now = now,
        )
    }

    // When a row the server ruled on may be offered again: doubling from a
    // minute, capped at an hour. Twenty-five attempts at that pace retire the
    // row within a day, and in the meantime every drain leaves it alone and
    // gets on with the rows around it.
    private fun deferral(attemptsSoFar: Int): Long {
        val step = minOf(attemptsSoFar, ROW_BACKOFF_DOUBLINGS)
        return now() + minOf(ROW_BACKOFF_MAX_MILLIS, ROW_BACKOFF_BASE_MILLIS shl step)
    }

    private suspend fun retireWhatWillNeverBeTaken(tally: DrainTally) {
        tally.unreachable = dao.markUnreachable(
            OutboxKind.IRREPLACEABLE,
            MAX_ATTEMPTS,
            MAX_REVIVALS,
            now(),
        )
        if (tally.unreachable > 0) {
            Log.w(
                TAG,
                "${tally.unreachable} row(s) failed again after $MAX_REVIVALS revivals; " +
                    "they are the payload's problem, not the link's, and stay set aside",
            )
        }

        tally.deadLettered = dao.markDead(OutboxKind.IRREPLACEABLE, MAX_ATTEMPTS, now())
        if (tally.deadLettered > 0) {
            Log.w(
                TAG,
                "${tally.deadLettered} irreplaceable row(s) exhausted the retry budget; " +
                    "they are kept and counted until something delivers again",
            )
        }
        tally.deadLettered += tally.unreachable
    }

    private suspend fun reviveWhatOnlyRanOutOfRetries(tally: DrainTally, outcome: Outcome) {
        if (outcome != Outcome.DONE) return
        tally.revived = when {
            tally.delivered -> dao.reviveExhausted(ATTEMPTS_AFTER_REVIVAL)

            !tally.attempted && tally.deadLettered == 0 ->
                dao.reviveOldestExhausted(REVIVAL_PROBE, ATTEMPTS_AFTER_REVIVAL)

            else -> 0
        }
        if (tally.revived > 0) {
            learnPayloadLimit(0, now())
            Log.i(
                TAG,
                "Queueing ${tally.revived} row(s) that had only run out of retries " +
                    if (tally.delivered) "; delivery is working again" else " to test delivery",
            )
        }
    }

    private suspend fun learn(tally: DrainTally, limitBytes: Long?) {
        if (limitBytes == null || limitBytes <= 0) return
        tally.payloadLimit = limitBytes
        if (tally.persistedLimit == limitBytes) return
        learnPayloadLimit(limitBytes, now())
        tally.persistedLimit = limitBytes
    }

    private fun reportUnreadableSettings(tally: DrainTally, cause: Throwable): DrainReport {
        val reason = cause.message ?: cause.javaClass.simpleName
        Log.w(TAG, "Cannot read the device configuration; nothing can be uploaded: $reason")
        tally.lastError = reason
        return tally.report(Outcome.RETRY)
    }

    private class Wire<T>(
        val kind: String,
        val suffix: String,
        val batchSize: Int,
        val item: KSerializer<T>,
        val encodeBatch: (List<T>) -> String,
    )

    private suspend fun <T> drainBatch(
        wire: Wire<T>,
        settings: Settings,
        tally: DrainTally,
    ): Outcome {
        var budget = BATCHES_PER_DRAIN
        var size = wire.batchSize
        // Rows the server ruled on during this drain. They are excluded from
        // every further take of this pass and scheduled on their own retryAfter,
        // so the drain's own outcome no longer carries their verdict: what was
        // due and deliverable went, and there is nothing transient to wait for.
        // retryAfter alone does not exclude them: a pass carrying recordings
        // runs for minutes, longer than the shortest deferral.
        val poisoned = mutableSetOf<Long>()
        while (true) {
            if (budget-- <= 0) {
                tally.moreWork = true
                return Outcome.DONE
            }
            val entries = dao.take(wire.kind, size + poisoned.size, now())
                .filter { it.id !in poisoned }
            debug(TAG) {
                "${wire.kind}: took ${entries.size} at batch size $size, " +
                    "${poisoned.size} deferred this pass, $budget batch(es) left"
            }
            if (entries.isEmpty()) return Outcome.DONE
            val kind = wire.kind

            val unreadable = mutableListOf<OutboxEntry>()
            val items = entries.mapNotNull { entry ->
                attempt { WireJson.decodeFromString(wire.item, entry.payload) }
                    .getOrElse {
                        unreadable += entry
                        null
                    }
            }
            if (unreadable.isNotEmpty()) {
                abandon(
                    tally,
                    kind,
                    unreadable.map { it.id },
                    undecodableReason(unreadable),
                    counter = tally.undecodable,
                    deadReason = DeadReason.UNDECODABLE,
                )
            }
            if (items.isEmpty()) continue

            val sent = entries.map { it.id } - unreadable.map { it.id }.toSet()
            tally.attempted = true
            val outcome = attempt {
                client.post(settings, wire.suffix, wire.encodeBatch(items))
            }.getOrElse { return failBatch(tally, sent, it) }
            debug(TAG) { "${wire.suffix} with ${sent.size} row(s) answered $outcome" }
            when (outcome) {
                is UploadOutcome.Success -> {
                    tally.delivered = true
                    learn(tally, outcome.limitBytes)
                    if (outcome.accepted > 0) tally.note(tally.accepted, kind, outcome.accepted)
                    if (outcome.duplicates > 0) {
                        tally.note(tally.duplicates, kind, outcome.duplicates)
                    }
                    val refused = outcome.skippedIndexes.distinct()
                        .mapNotNull { sent.getOrNull(it) }
                    if (refused.isNotEmpty()) {
                        abandon(tally, kind, refused, "server read it and could not store it")
                    } else if (outcome.skipped > 0) {
                        noteSkipped(tally, kind, outcome.skipped)
                    }
                    dao.delete(sent - refused.toSet())
                }

                is UploadOutcome.Duplicate -> {
                    tally.delivered = true
                    tally.note(tally.duplicates, kind, sent.size)
                    dao.delete(sent)
                }

                is UploadOutcome.Rejected -> {
                    abandon(tally, kind, sent, "server refused the payload (${outcome.code})")
                }

                is UploadOutcome.TooLarge -> {
                    learn(tally, outcome.limitBytes)
                    if (sent.size > 1) {
                        size = halved(size, kind, outcome.describe())
                        continue
                    }
                    poisoned += deferRuledOn(
                        tally,
                        entries.single { it.id in sent },
                        "one row on its own is ${outcome.describe()}",
                    )
                    continue
                }

                is UploadOutcome.Retry -> {
                    if (outcome.serverFault && sent.size > 1) {
                        size = halved(size, kind, outcome.reason)
                        continue
                    }
                    if (!outcome.serverFault) {
                        dao.markFailed(sent, outcome.reason)
                        tally.lastError = outcome.reason
                        return Outcome.RETRY
                    }
                    poisoned +=
                        deferRuledOn(tally, entries.single { it.id in sent }, outcome.reason)
                    size = wire.batchSize
                    continue
                }
            }
            if (entries.size < size) return Outcome.DONE
        }
    }

    private fun undecodableReason(entries: List<OutboxEntry>): String {
        val versions = entries.map { it.payloadVersion }.distinct().sorted()
        val written = versions.joinToString(", ")
        return if (versions == listOf(CURRENT_PAYLOAD_VERSION)) {
            "payload could not be decoded by this build, which wrote it"
        } else {
            "payload was written in queue format $written and this build reads " +
                "$CURRENT_PAYLOAD_VERSION"
        }
    }

    private fun halved(size: Int, kind: String, why: String): Int {
        val narrowed = maxOf(1, size / 2)
        Log.w(TAG, "Narrowing the $kind batch from $size to $narrowed: $why")
        return narrowed
    }

    private fun UploadOutcome.TooLarge.describe(): String =
        limitBytes?.let { "over the server's ${it / 1024} KB payload cap" }
            ?: "over the server's payload cap"

    private suspend fun drainRecordings(settings: Settings, tally: DrainTally): Outcome {
        var budget = RECORDING_BYTES_PER_DRAIN
        // The same exclusion as drainBatch's, for the same reason.
        val poisoned = mutableSetOf<Long>()
        while (true) {
            val entries = dao.take(OutboxKind.RECORDING, RECORDING_BATCH + poisoned.size, now())
                .filter { it.id !in poisoned }
            if (entries.isEmpty()) return Outcome.DONE
            for (entry in entries) {
                val file = entry.filePath?.let(::File)
                if (file == null || !file.exists()) {
                    forgetLostAudio(tally, entry)
                    continue
                }
                if (file.length() > MAX_RECORDING_BYTES) {
                    poisoned += deferRuledOn(
                        tally,
                        entry,
                        "recording of ${file.length()} bytes is over the " +
                            "${MAX_RECORDING_BYTES / 1024 / 1024} MB any mobile device accepts",
                    )
                    continue
                }
                if (budget <= 0) {
                    tally.moreWork = true
                    return Outcome.DONE
                }

                val metadata = attempt {
                    WireJson.encodeToJsonElement(
                        WireJson.decodeFromString<RecordingMetadata>(entry.payload),
                    ).jsonObject
                }.getOrElse {
                    abandon(
                        tally,
                        OutboxKind.RECORDING,
                        listOf(entry.id),
                        undecodableReason(listOf(entry)),
                        counter = tally.undecodable,
                        deadReason = DeadReason.UNDECODABLE,
                    )
                    continue
                }
                val wireSize = client.recordingWireSize(file, metadata)
                val declared = tally.payloadLimit
                debug(TAG) {
                    "recording row ${entry.id}: ${file.length()} bytes, $wireSize on the wire, " +
                        "cap $declared, attempt ${entry.attempts + 1}, $budget budget left"
                }
                if (declared in 1..<wireSize) {
                    poisoned += deferRuledOn(
                        tally,
                        entry,
                        "recording would send $wireSize bytes and the server declared a " +
                            "${declared / 1024} KB cap, so it is not sent",
                    )
                    continue
                }
                budget -= wireSize
                tally.attempted = true

                val outcome = attempt {
                    client.post(settings, "recording", file, metadata)
                }.getOrElse { return failBatch(tally, listOf(entry.id), it) }
                debug(TAG) { "recording row ${entry.id} answered $outcome" }
                when (outcome) {
                    is UploadOutcome.Success, is UploadOutcome.Duplicate -> {
                        tally.delivered = true
                        learn(tally, (outcome as? UploadOutcome.Success)?.limitBytes)
                        if (outcome is UploadOutcome.Duplicate) {
                            tally.note(tally.duplicates, OutboxKind.RECORDING, 1)
                        } else {
                            tally.note(tally.accepted, OutboxKind.RECORDING, 1)
                        }
                        if (deleteAudio(file)) {
                            dao.delete(listOf(entry.id))
                        } else {
                            // With no cursor, the harvest's only memory is
                            // this table. Drop the row and the file comes
                            // straight back as a new recording.
                            dao.markDeadIds(
                                listOf(entry.id),
                                now(),
                                "uploaded, but the file could not be deleted",
                                DeadReason.KEPT_ON_DISK,
                            )
                        }
                    }

                    is UploadOutcome.Rejected -> abandon(
                        tally,
                        OutboxKind.RECORDING,
                        listOf(entry.id),
                        "server refused the recording (${outcome.code})",
                    )

                    is UploadOutcome.TooLarge -> {
                        learn(tally, outcome.limitBytes)
                        poisoned += deferRuledOn(
                            tally,
                            entry,
                            "recording of ${file.length()} bytes is ${outcome.describe()}",
                        )
                    }

                    is UploadOutcome.Retry -> {
                        if (!outcome.serverFault) {
                            dao.markFailed(listOf(entry.id), outcome.reason)
                            tally.lastError = outcome.reason
                            return Outcome.RETRY
                        }
                        poisoned += deferRuledOn(tally, entry, outcome.reason)
                    }
                }
            }
        }
    }

    private suspend fun forgetLostAudio(tally: DrainTally, entry: OutboxEntry) {
        val folder = entry.filePath.orEmpty().loggableDirectory()
        dao.delete(listOf(entry.id))
        tally.lost++
        Log.w(
            TAG,
            "Dropping recording row ${entry.id}: its audio is no longer under " +
                "$folder, so nothing can be uploaded",
        )
    }

    private suspend fun abandon(
        tally: DrainTally,
        kind: String,
        ids: List<Long>,
        reason: String,
        counter: MutableMap<String, Int> = tally.skipped,
        deadReason: String = DeadReason.REFUSED,
    ) {
        if (ids.isEmpty()) return
        tally.note(counter, kind, ids.size)

        Log.w(TAG, "Setting aside ${ids.size} $kind row(s): $reason")
        if (kind in OutboxKind.IRREPLACEABLE) {
            dao.markDeadIds(ids, now(), reason, deadReason)
            tally.deadLettered += ids.size
        } else {
            dao.delete(ids)
            tally.discarded += ids.size
        }
    }

    // A row whose audio will not delete is the harvest's only memory of that
    // file: purge it and the next scan queues and uploads the file again. It
    // starts another retention period instead.
    private suspend fun purgeExpiredDeadRows(tally: DrainTally) {
        val before = now() - OutboxLimits.DEAD_RETENTION_MILLIS
        dao.deadFilesBefore(before)
            .filterNot { deleteAudio(File(it.filePath)) }
            .map { it.id }
            .chunked(SQLITE_MAX_PARAMETERS)
            .forEach { dao.restampDead(it, now()) }
        val purged = dao.deleteDeadBefore(before)
        if (purged == 0) return
        tally.purged += purged
        Log.i(TAG, "Purged $purged row(s) that had been undeliverable for 90 days")
    }

    private fun deleteAudio(file: File): Boolean {
        if (!file.exists() || file.delete()) return true
        val folder = file.absolutePath.loggableDirectory()
        Log.w(TAG, "Could not delete an uploaded recording under $folder")
        return false
    }

    private fun noteSkipped(tally: DrainTally, kind: String, count: Int) {
        tally.note(tally.skipped, kind, count)
        Log.w(TAG, "Server could not store $count $kind row(s); they are not retried")
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (stopped: CancellationException) {
        throw stopped
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    // A row the server has ruled on -- it raised on it, or it is over the cap
    // -- is charged and waits on its own retryAfter; the rest of the queue
    // keeps moving. Returning RETRY instead parked every row behind it and
    // walked the whole worker into WorkManager's five-hour backoff.
    private suspend fun deferRuledOn(tally: DrainTally, entry: OutboxEntry, reason: String): Long {
        Log.w(TAG, "Keeping ${entry.kind} row ${entry.id} the server would not take: $reason")
        dao.markFailed(
            listOf(entry.id),
            reason,
            serverFault = true,
            retryAfter = deferral(entry.attempts),
        )
        tally.lastError = reason
        return entry.id
    }

    private suspend fun failBatch(tally: DrainTally, ids: List<Long>, cause: Throwable): Outcome {
        val reason = cause.message ?: cause.javaClass.simpleName
        dao.markFailed(ids, reason)
        tally.lastError = reason
        return Outcome.RETRY
    }

    companion object {
        const val LOCATION_BATCH = 200
        const val CALL_LOG_BATCH = 100
        const val RECORDING_BATCH = 3
        const val MAX_ATTEMPTS = 25

        const val MAX_REVIVALS = 3

        const val ATTEMPTS_AFTER_REVIVAL = MAX_ATTEMPTS - 1

        const val MAX_RECORDING_BYTES = 36L * 1024 * 1024

        const val RECORDING_BYTES_PER_DRAIN = 64L * 1024 * 1024

        const val BATCHES_PER_DRAIN = 25

        const val ROW_BACKOFF_BASE_MILLIS = 60_000L
        const val ROW_BACKOFF_MAX_MILLIS = 60L * 60 * 1000
        private const val ROW_BACKOFF_DOUBLINGS = 6

        const val REVIVAL_PROBE = 1

        // Android 10-11 ship SQLite 3.28, whose host-parameter limit is 999.
        private const val SQLITE_MAX_PARAMETERS = 900

        private const val TAG = "OutboxDrainer"

        private val LOCATIONS = Wire(
            OutboxKind.LOCATION,
            "location",
            LOCATION_BATCH,
            LocationFix.serializer(),
        ) { WireJson.encodeToString(LocationUpload(it)) }

        private val CALLS = Wire(
            OutboxKind.CALL_LOG,
            "calllog",
            CALL_LOG_BATCH,
            CallRecord.serializer(),
        ) { WireJson.encodeToString(CallLogUpload(it)) }

        private val drainLock = Mutex()
    }
}

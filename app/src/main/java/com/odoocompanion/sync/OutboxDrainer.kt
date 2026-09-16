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
    private val learnPayloadLimit: suspend (Long) -> Unit = {},
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
        return tally.report(outcome)
    }

    private suspend fun anythingArrivedLate(windowSeconds: Long): Boolean {
        if (OutboxKind.IRREPLACEABLE.any { dao.countOf(it) > 0 }) return true
        val positions = dao.depthOf(OutboxKind.LOCATION)
        return UploadCadence.dueNow(
            queued = positions.queued,
            oldestCreatedAt = positions.oldestCreatedAt,
            windowSeconds = windowSeconds,
            now = now(),
        )
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
            learnPayloadLimit(0)
            Log.i(
                TAG,
                "Queueing ${tally.revived} row(s) that had only run out of retries " +
                    if (tally.delivered) "; delivery is working again" else " to test delivery",
            )
        }
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
        val poisoned = mutableSetOf<Long>()
        val settled = { if (poisoned.isEmpty()) Outcome.DONE else Outcome.RETRY }
        while (true) {
            if (budget-- <= 0) {
                tally.moreWork = true
                return settled()
            }
            val entries = dao.take(wire.kind, size + poisoned.size).filter { it.id !in poisoned }
            if (entries.isEmpty()) return settled()
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
            when (outcome) {
                is UploadOutcome.Success -> {
                    tally.delivered = true
                    outcome.limitBytes?.let { learnPayloadLimit(it) }
                    if (outcome.accepted > 0) tally.note(tally.accepted, kind, outcome.accepted)
                    if (outcome.duplicates > 0) {
                        tally.note(tally.duplicates, kind, outcome.duplicates)
                    }
                    val refused = outcome.skippedIndexes.mapNotNull { sent.getOrNull(it) }
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
                    outcome.limitBytes?.let { learnPayloadLimit(it) }
                    if (sent.size > 1) {
                        size = halved(size, kind, outcome.describe())
                        continue
                    }
                    return failLargeBatch(
                        tally,
                        sent,
                        "one row on its own is ${outcome.describe()}",
                    )
                }

                is UploadOutcome.Retry -> {
                    if (outcome.serverFault && sent.size > 1) {
                        size = halved(size, kind, outcome.reason)
                        continue
                    }
                    dao.markFailed(sent, outcome.reason, outcome.serverFault)
                    tally.lastError = outcome.reason
                    if (!outcome.serverFault) return Outcome.RETRY
                    poisoned += sent
                    continue
                }
            }
            if (entries.size < size) return settled()
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
        val poisoned = mutableSetOf<Long>()
        val settled = { if (poisoned.isEmpty()) Outcome.DONE else Outcome.RETRY }
        while (true) {
            val entries = dao.take(OutboxKind.RECORDING, RECORDING_BATCH + poisoned.size)
                .filter { it.id !in poisoned }
            if (entries.isEmpty()) return settled()
            for (entry in entries) {
                val file = entry.filePath?.let(::File)
                if (file == null || !file.exists()) {
                    forgetLostAudio(tally, entry)
                    continue
                }
                if (file.length() > MAX_RECORDING_BYTES) {
                    return failLargeBatch(
                        tally,
                        listOf(entry.id),
                        "recording of ${file.length()} bytes is over the " +
                            "${MAX_RECORDING_BYTES / 1024 / 1024} MB any mobile device accepts",
                    )
                }
                if (budget <= 0) {
                    tally.moreWork = true
                    return settled()
                }

                val metadata = attempt {
                    WireJson.encodeToJsonElement(
                        WireJson.decodeFromString<RecordingMetadata>(entry.payload),
                    ).jsonObject
                }.getOrElse { return failBatch(tally, listOf(entry.id), it) }
                val wireSize = client.recordingWireSize(file, metadata)
                val declared = settings.payloadLimit(now())
                if (declared in 1..<wireSize) {
                    return failLargeBatch(
                        tally,
                        listOf(entry.id),
                        "recording would send $wireSize bytes and the server declared a " +
                            "${declared / 1024} KB cap, so it is not sent",
                    )
                }
                budget -= file.length()
                tally.attempted = true

                val outcome = attempt {
                    client.post(settings, "recording", file, metadata)
                }.getOrElse { return failBatch(tally, listOf(entry.id), it) }
                when (outcome) {
                    is UploadOutcome.Success, is UploadOutcome.Duplicate -> {
                        tally.delivered = true
                        (outcome as? UploadOutcome.Success)?.limitBytes?.let {
                            learnPayloadLimit(it)
                        }
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
                        outcome.limitBytes?.let { learnPayloadLimit(it) }
                        return failLargeBatch(
                            tally,
                            listOf(entry.id),
                            "recording of ${file.length()} bytes is ${outcome.describe()}",
                        )
                    }

                    is UploadOutcome.Retry -> {
                        dao.markFailed(listOf(entry.id), outcome.reason, outcome.serverFault)
                        tally.lastError = outcome.reason
                        if (!outcome.serverFault) return Outcome.RETRY
                        poisoned += entry.id
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

    private suspend fun purgeExpiredDeadRows(tally: DrainTally) {
        val before = now() - OutboxLimits.DEAD_RETENTION_MILLIS
        dao.deadFilesBefore(before).forEach { deleteAudio(File(it)) }
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

    private suspend fun failLargeBatch(
        tally: DrainTally,
        ids: List<Long>,
        reason: String,
    ): Outcome {
        Log.w(TAG, "Keeping ${ids.size} row(s) the server would not take: $reason")
        dao.markFailed(ids, reason, serverFault = true)
        tally.lastError = reason
        return Outcome.RETRY
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

        const val REVIVAL_PROBE = 1

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

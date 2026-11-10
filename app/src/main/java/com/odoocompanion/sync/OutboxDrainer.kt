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
import com.odoocompanion.net.DocumentMetadata
import com.odoocompanion.net.FileBody
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
import kotlinx.serialization.json.JsonObject
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
    var linkDown = false

    var payloadLimit = 0L
    var persistedLimit: Long? = null

    var bytesLeft = OutboxDrainer.FILE_BYTES_PER_DRAIN

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
        withdrawLongHeld(settings, tally)
        val outcome = drainUntilTheLinkFails(settings, tally)
        reviveWhatOnlyRanOutOfRetries(settings, tally, outcome)

        if (outcome == Outcome.DONE && !tally.moreWork) {
            tally.moreWork = anythingArrivedLate(settings)
        }
        return tally.report(outcome).also { debug(TAG) { "drain finished: $it" } }
    }

    private suspend fun drainUntilTheLinkFails(settings: Settings, tally: DrainTally): Outcome {
        val kinds: List<suspend () -> Outcome> = listOf(
            { drainBatch(LOCATIONS, settings, tally) },
            { drainBatch(CALLS, settings, tally) },
            { drainFiles(DOCUMENTS, settings, tally) },
            { drainFiles(RECORDINGS, settings, tally) },
        )
        var outcome = Outcome.DONE
        for (drainKind in kinds) {
            if (drainKind() != Outcome.RETRY) continue
            outcome = Outcome.RETRY
            if (tally.linkDown) break
        }
        return outcome
    }

    private suspend fun anythingArrivedLate(settings: Settings): Boolean {
        val now = now()
        val enrollment = settings.enrollment
        if (OutboxKind.IRREPLACEABLE.any { dao.countOf(it, now, enrollment) > 0 }) return true
        val positions = dao.depthOf(OutboxKind.LOCATION, now, enrollment)
        return UploadCadence.dueNow(
            queued = positions.queued,
            oldestCreatedAt = positions.oldestCreatedAt,
            windowSeconds = settings.uploadWindowSeconds,
            now = now,
        )
    }

    private suspend fun withdrawLongHeld(settings: Settings, tally: DrainTally) {
        val enrollment = settings.enrollment ?: return
        val before = now() - OutboxLimits.DEAD_RETENTION_MILLIS
        val positions = dao.deleteHeldPositions(enrollment, before = before)
        val withdrawn = dao.withdrawHeld(enrollment, from = null, now = now(), before = before)
        tally.discarded += positions
        if (positions + withdrawn > 0) {
            Log.i(
                TAG,
                "Withdrew ${positions + withdrawn} row(s) of a previous enrolment, collected 90 days ago",
            )
        }
    }

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

    private suspend fun reviveWhatOnlyRanOutOfRetries(
        settings: Settings,
        tally: DrainTally,
        outcome: Outcome,
    ) {
        if (outcome != Outcome.DONE) return
        val enrollment = settings.enrollment
        tally.revived = when {
            tally.delivered -> dao.reviveExhausted(ATTEMPTS_AFTER_REVIVAL, enrollment)

            !tally.attempted && tally.deadLettered == 0 ->
                dao.reviveOldestExhausted(REVIVAL_PROBE, ATTEMPTS_AFTER_REVIVAL, enrollment)

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

    private class FileKind(
        val kind: String,
        val suffix: String,
        val noun: String,
        val fileKey: String,
        val ownsFile: Boolean,
        val metadataOf: (String) -> JsonObject,
    )

    private suspend fun <T> drainBatch(
        wire: Wire<T>,
        settings: Settings,
        tally: DrainTally,
    ): Outcome {
        var budget = BATCHES_PER_DRAIN
        var size = wire.batchSize
        val poisoned = mutableSetOf<Long>()
        while (true) {
            if (budget-- <= 0) {
                tally.moreWork = true
                return Outcome.DONE
            }
            val entries = dao.take(wire.kind, size + poisoned.size, now(), settings.enrollment)
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
                        return chargeTheLink(tally, sent, outcome.reason, outcome.linkDown)
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

    private suspend fun drainFiles(
        files: FileKind,
        settings: Settings,
        tally: DrainTally,
    ): Outcome {
        val poisoned = mutableSetOf<Long>()
        while (true) {
            val entries =
                dao.take(files.kind, FILE_BATCH + poisoned.size, now(), settings.enrollment)
                    .filter { it.id !in poisoned }
            if (entries.isEmpty()) return Outcome.DONE
            for (entry in entries) {
                val file = entry.filePath?.let(::File)
                if (file == null || !file.exists()) {
                    forgetLostFile(tally, entry, files)
                    continue
                }
                if (file.length() > MAX_FILE_BYTES) {
                    poisoned += deferRuledOn(
                        tally,
                        entry,
                        "${files.noun} of ${file.length()} bytes is over the " +
                            "${MAX_FILE_BYTES / 1024 / 1024} MB any mobile device accepts",
                    )
                    continue
                }
                if (tally.bytesLeft <= 0) {
                    tally.moreWork = true
                    return Outcome.DONE
                }

                val metadata = attempt {
                    files.metadataOf(entry.payload)
                }.getOrElse {
                    abandon(
                        tally,
                        files.kind,
                        listOf(entry.id),
                        undecodableReason(listOf(entry)),
                        counter = tally.undecodable,
                        deadReason = DeadReason.UNDECODABLE,
                    )
                    continue
                }
                val wireSize = client.wireSize(file, metadata, files.fileKey)
                val declared = tally.payloadLimit
                debug(TAG) {
                    "${files.noun} row ${entry.id}: ${file.length()} bytes, " +
                        "$wireSize on the wire, cap $declared, " +
                        "attempt ${entry.attempts + 1}, ${tally.bytesLeft} budget left"
                }
                if (declared in 1..<wireSize) {
                    poisoned += deferRuledOn(
                        tally,
                        entry,
                        "${files.noun} would send $wireSize bytes and the server declared a " +
                            "${declared / 1024} KB cap, so it is not sent",
                    )
                    continue
                }
                tally.bytesLeft -= wireSize
                tally.attempted = true

                val outcome = attempt {
                    client.post(settings, files.suffix, file, metadata, files.fileKey)
                }.getOrElse { return failBatch(tally, listOf(entry.id), it) }
                debug(TAG) { "${files.noun} row ${entry.id} answered $outcome" }
                when (outcome) {
                    is UploadOutcome.Success, is UploadOutcome.Duplicate -> {
                        tally.delivered = true
                        val success = outcome as? UploadOutcome.Success
                        learn(tally, success?.limitBytes)
                        if (success == null || success.duplicates > 0) {
                            tally.note(tally.duplicates, files.kind, 1)
                        } else {
                            tally.note(tally.accepted, files.kind, 1)
                        }
                        if (!files.ownsFile || deleteAudio(file)) {
                            dao.delete(listOf(entry.id))
                        } else {
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
                        files.kind,
                        listOf(entry.id),
                        "server refused the ${files.noun} (${outcome.code})",
                    )

                    is UploadOutcome.TooLarge -> {
                        learn(tally, outcome.limitBytes)
                        poisoned += deferRuledOn(
                            tally,
                            entry,
                            "${files.noun} of ${file.length()} bytes is ${outcome.describe()}",
                        )
                    }

                    is UploadOutcome.Retry -> {
                        if (!outcome.serverFault) {
                            return chargeTheLink(
                                tally,
                                listOf(entry.id),
                                outcome.reason,
                                outcome.linkDown,
                            )
                        }
                        poisoned += deferRuledOn(tally, entry, outcome.reason)
                    }
                }
            }
        }
    }

    private suspend fun forgetLostFile(tally: DrainTally, entry: OutboxEntry, files: FileKind) {
        val folder = entry.filePath.orEmpty().loggableDirectory()
        val reason = "its file is no longer under $folder, so nothing can be uploaded"
        val verb = if (files.ownsFile) {
            dao.delete(listOf(entry.id))
            "Dropping"
        } else {
            dao.markDeadIds(listOf(entry.id), now(), reason, DeadReason.GONE)
            "Setting aside"
        }
        tally.lost++
        Log.w(TAG, "$verb ${files.noun} row ${entry.id}: $reason")
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

    private suspend fun failBatch(tally: DrainTally, ids: List<Long>, cause: Throwable): Outcome =
        chargeTheLink(tally, ids, cause.message ?: cause.javaClass.simpleName, linkDown = false)

    private suspend fun chargeTheLink(
        tally: DrainTally,
        ids: List<Long>,
        reason: String,
        linkDown: Boolean,
    ): Outcome {
        dao.markFailed(ids, reason)
        tally.lastError = reason
        tally.linkDown = linkDown
        return Outcome.RETRY
    }

    companion object {
        const val LOCATION_BATCH = 200
        const val CALL_LOG_BATCH = 100
        const val FILE_BATCH = 3
        const val MAX_ATTEMPTS = 25

        const val MAX_REVIVALS = 3

        const val ATTEMPTS_AFTER_REVIVAL = MAX_ATTEMPTS - 1

        const val MAX_FILE_BYTES = 36L * 1024 * 1024

        const val FILE_BYTES_PER_DRAIN = 64L * 1024 * 1024

        const val BATCHES_PER_DRAIN = 25

        const val ROW_BACKOFF_BASE_MILLIS = 60_000L
        const val ROW_BACKOFF_MAX_MILLIS = 60L * 60 * 1000
        private const val ROW_BACKOFF_DOUBLINGS = 6

        const val REVIVAL_PROBE = 1

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

        private val RECORDINGS = FileKind(
            OutboxKind.RECORDING,
            "recording",
            "recording",
            FileBody.AUDIO_KEY,
            ownsFile = true,
        ) {
            WireJson.encodeToJsonElement(WireJson.decodeFromString<RecordingMetadata>(it))
                .jsonObject
        }

        private val DOCUMENTS = FileKind(
            OutboxKind.DOCUMENT,
            "document",
            "document",
            FileBody.FILE_KEY,
            ownsFile = false,
        ) {
            WireJson.encodeToJsonElement(WireJson.decodeFromString<DocumentMetadata>(it))
                .jsonObject
        }

        private val drainLock = Mutex()
    }
}

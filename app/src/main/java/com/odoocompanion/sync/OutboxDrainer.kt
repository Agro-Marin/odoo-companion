package com.odoocompanion.sync

import android.database.sqlite.SQLiteException
import android.util.Log
import com.odoocompanion.config.Settings
import com.odoocompanion.data.CURRENT_PAYLOAD_VERSION
import com.odoocompanion.data.DeadReason
import com.odoocompanion.data.FileStamp
import com.odoocompanion.data.OutboxDao
import com.odoocompanion.data.OutboxEntry
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.data.OutboxLimits
import com.odoocompanion.data.QueuedFile
import com.odoocompanion.net.CallLogUpload
import com.odoocompanion.net.CallRecord
import com.odoocompanion.net.DocumentMetadata
import com.odoocompanion.net.FileBody
import com.odoocompanion.net.LocationFix
import com.odoocompanion.net.LocationUpload
import com.odoocompanion.net.OdooClient
import com.odoocompanion.net.RecordingMetadata
import com.odoocompanion.net.RetryScope
import com.odoocompanion.net.UploadOutcome
import com.odoocompanion.net.WireJson
import com.odoocompanion.system.debug
import com.odoocompanion.whatsapp.DocumentReader
import com.odoocompanion.whatsapp.QueuedDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import java.nio.file.NoSuchFileException

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
    val deliveredDocuments: List<QueuedDocument> = emptyList(),
    val deferred: Boolean = false,
    val moreWorkPending: Boolean = false,
)

private class DrainTally(val readyAt: Long) {
    val accepted = mutableMapOf<String, Int>()
    val skipped = mutableMapOf<String, Int>()
    val duplicates = mutableMapOf<String, Int>()
    val undecodable = mutableMapOf<String, Int>()
    var lastError: String? = null

    var delivered = false

    val deliveredDocuments = mutableListOf<QueuedDocument>()

    var attempted = false
    var deadLettered = 0
    var unreachable = 0
    var discarded = 0
    var lost = 0
    var purged = 0
    var revived = 0
    var moreWork = false
    var stopAfterKind = false

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
        deliveredDocuments = deliveredDocuments.toList(),
        moreWorkPending = moreWork,
    )
}

class OutboxDrainer(
    private val dao: OutboxDao,
    private val client: OdooClient,
    private val now: () -> Long = System::currentTimeMillis,
    private val learnPayloadLimit: suspend (bytes: Long, at: Long) -> Unit = { _, _ -> },
    rememberFiles: suspend (String, List<QueuedFile>) -> Unit = { _, _ -> },
    private val documentReader: DocumentReader =
        DocumentReader(File(requireNotNull(System.getProperty("java.io.tmpdir")))),
    private val withCurrentCredentials: suspend (Settings, suspend () -> Unit) -> Boolean =
        { _, action ->
            action()
            true
        },
    private val settingsProvider: suspend () -> Settings,
) {
    private val cleanup = FileCleanup(dao, rememberFiles)

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
        val tally = DrainTally(now())

        retireWhatWillNeverBeTaken(tally)
        tally.discarded = dao.deleteExhausted(OutboxKind.IRREPLACEABLE, MAX_ATTEMPTS)
        tally.purged = cleanup.collect(now())

        val settings = attempt { settingsProvider() }.getOrElse { cause ->
            return reportUnreadableSettings(tally, cause)
        }
        tally.payloadLimit = settings.payloadLimit(now())
        val enrollment = settings.enrollment
        if (enrollment != null &&
            !withCurrentCredentials(settings) { withdrawLongHeld(enrollment, tally) }
        ) {
            tally.lastError = "could not confirm the current enrolment"
            debug(TAG) { "Stopping before retention and upload: ${tally.lastError}" }
            return tally.report(Outcome.RETRY)
        }
        val outcome = drainUntilTheLinkFails(settings, tally)
        reviveWhatOnlyRanOutOfRetries(settings, tally, outcome)

        if (outcome == Outcome.DONE && !tally.moreWork) {
            tally.moreWork = anythingArrivedLate(settings)
        }
        debug(TAG) {
            "drain finished: $outcome, accepted=${tally.accepted}, skipped=${tally.skipped}, " +
                "duplicates=${tally.duplicates}, moreWork=${tally.moreWork}"
        }
        return tally.report(outcome)
    }

    private suspend fun drainUntilTheLinkFails(settings: Settings, tally: DrainTally): Outcome {
        val uploads = client.uploads(settings)
        val kinds: List<suspend () -> Outcome> = listOf(
            { drainBatch(LOCATIONS, uploads, tally) },
            { drainBatch(CALLS, uploads, tally) },
            { drainFiles(DOCUMENTS, uploads, tally) },
            { drainFiles(SCANS, uploads, tally) },
            { drainFiles(RECORDINGS, uploads, tally) },
        )
        var outcome = Outcome.DONE
        for (drainKind in kinds) {
            if (withQueueOwnership { drainKind() } != Outcome.RETRY) continue
            outcome = Outcome.RETRY
            if (tally.stopAfterKind) break
        }
        return outcome
    }

    private suspend fun anythingArrivedLate(settings: Settings): Boolean {
        val now = now()
        val enrollment = settings.enrollment
        if (dao.hasReady(OutboxKind.IRREPLACEABLE, now, enrollment)) return true
        val positions = dao.locationDepth(enrollment, UploadCadence.BATCH_THRESHOLD, now)
        return UploadCadence.dueNow(
            queued = positions.ready,
            oldestCreatedAt = positions.oldestCreatedAt,
            windowSeconds = settings.uploadWindowSeconds,
            now = now,
        )
    }

    private suspend fun withdrawLongHeld(enrollment: String, tally: DrainTally) {
        val at = now()
        val before = at - OutboxLimits.DEAD_RETENTION_MILLIS
        val (positions, withdrawn) = dao.discardHeld(enrollment, from = null, at, before)
        tally.discarded += positions
        if (positions + withdrawn > 0) {
            Log.i(
                TAG,
                "Withdrew ${positions + withdrawn} row(s) of a previous enrolment, collected 90 days ago",
            )
        }
    }

    private fun deferral(attemptsSoFar: Int, readyAt: Long): Long {
        val step = minOf(attemptsSoFar, ROW_BACKOFF_DOUBLINGS)
        return maxOf(readyAt, now()) +
            minOf(ROW_BACKOFF_MAX_MILLIS, ROW_BACKOFF_BASE_MILLIS shl step)
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
            persistLimit(0)
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
        if (persistLimit(limitBytes)) tally.persistedLimit = limitBytes
    }

    private suspend fun persistLimit(limit: Long): Boolean = try {
        learnPayloadLimit(limit, now())
        true
    } catch (failure: IOException) {
        Log.w(TAG, "Could not save the payload limit (${failure.javaClass.simpleName})")
        false
    }

    private fun reportUnreadableSettings(tally: DrainTally, cause: Throwable): DrainReport {
        val reason = "device configuration unavailable (${cause.javaClass.simpleName})"
        Log.w(TAG, reason)
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

    private class FilePayload(val metadata: JsonObject, val document: QueuedDocument? = null)

    private class FileKind(
        val kind: String,
        val suffix: String,
        val noun: String,
        val fileKey: String,
        val decode: (String, File, FileStamp) -> FilePayload,
    )

    private suspend fun take(
        kind: String,
        limit: Int,
        settings: Settings,
        tally: DrainTally,
    ): List<OutboxEntry>? {
        lateinit var rows: List<OutboxEntry>
        val confirmed = withCurrentCredentials(settings) {
            rows = dao.take(kind, limit, tally.readyAt, settings.enrollment)
        }
        if (confirmed) return rows

        credentialsUnavailable(tally)
        return null
    }

    private fun credentialsUnavailable(tally: DrainTally): Outcome {
        tally.lastError = "could not confirm the current enrolment"
        tally.stopAfterKind = true
        debug(TAG) { "Stopping this upload pass: ${tally.lastError}" }
        return Outcome.RETRY
    }

    private suspend fun post(
        settings: Settings,
        tally: DrainTally,
        send: suspend () -> UploadOutcome,
    ): UploadOutcome = coroutineScope {
        lateinit var request: Deferred<UploadOutcome>
        val confirmed = withCurrentCredentials(settings) {
            request = async(start = CoroutineStart.UNDISPATCHED) { send() }
        }
        if (!confirmed) return@coroutineScope UploadOutcome.CredentialsUnavailable
        tally.attempted = true
        request.await()
    }

    private suspend fun <T> drainBatch(
        wire: Wire<T>,
        uploads: OdooClient.Uploads,
        tally: DrainTally,
    ): Outcome {
        var budget = BATCHES_PER_DRAIN
        var size = wire.batchSize
        while (true) {
            if (budget-- <= 0) {
                tally.moreWork = true
                return Outcome.DONE
            }
            val entries = take(wire.kind, size, uploads.settings, tally) ?: return Outcome.RETRY
            debug(TAG) {
                "${wire.kind}: took ${entries.size} at batch size $size, " +
                    "$budget batch(es) left"
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
            val outcome = attempt {
                val body = wire.encodeBatch(items)
                post(uploads.settings, tally) { uploads.post(wire.suffix, body) }
            }.getOrElse { return failBatch(tally, sent, it) }
            debug(TAG) {
                "${wire.suffix} with ${sent.size} row(s) answered ${outcome.diagnostic()}"
            }
            when (outcome) {
                UploadOutcome.CredentialsUnavailable -> return credentialsUnavailable(tally)

                is UploadOutcome.Success -> {
                    learn(tally, outcome.limitBytes)
                    if (outcome.accepted > 0) tally.note(tally.accepted, kind, outcome.accepted)
                    if (outcome.duplicates > 0) {
                        tally.note(tally.duplicates, kind, outcome.duplicates)
                    }
                    val refused = if (outcome.skipped == sent.size &&
                        outcome.accepted == 0 && outcome.duplicates == 0
                    ) {
                        sent
                    } else {
                        outcome.skippedIndexes.distinct().mapNotNull { sent.getOrNull(it) }
                    }
                    if (refused.isNotEmpty()) {
                        abandon(tally, kind, refused, "server read it and could not store it")
                    }
                    if (outcome.skipped > refused.size) {
                        noteSkipped(tally, kind, outcome.skipped - refused.size)
                    }
                    if (refused.size < sent.size) {
                        tally.delivered = true
                        dao.delete(sent - refused.toSet())
                    }
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
                        size = halved(sent.size, kind, outcome.describe())
                        continue
                    }
                    deferRuledOn(
                        tally,
                        entries.single { it.id in sent },
                        "one row on its own is ${outcome.describe()}",
                    )
                    continue
                }

                is UploadOutcome.Retry -> {
                    if (outcome.scope == RetryScope.ROW && sent.size > 1) {
                        size = halved(sent.size, kind, outcome.reason)
                        continue
                    }
                    if (outcome.scope != RetryScope.ROW) {
                        return chargeTheLink(
                            tally,
                            sent,
                            outcome.reason,
                            outcome.scope == RetryScope.LINK
                        )
                    }
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

    private fun UploadOutcome.diagnostic(): String = when (this) {
        is UploadOutcome.Retry -> "Retry($scope)"
        else -> toString()
    }

    private fun UploadOutcome.TooLarge.describe(): String =
        limitBytes?.let { "over the server's ${it / 1024} KB payload cap" }
            ?: "over the server's payload cap"

    private suspend fun drainFiles(
        files: FileKind,
        uploads: OdooClient.Uploads,
        tally: DrainTally,
    ): Outcome {
        while (true) {
            val entries =
                take(files.kind, FILE_BATCH, uploads.settings, tally) ?: return Outcome.RETRY
            if (entries.isEmpty()) return Outcome.DONE
            for (entry in entries) {
                val file = entry.filePath?.let(::File)
                if (file == null) {
                    forgetLostFile(tally, entry, files)
                    continue
                }
                val source = try {
                    FileStamp.read(file)?.takeIf { current ->
                        entry.kind == OutboxKind.SCAN ||
                            current.matchesQueuedMetadata(entry.source, entry.createdAt)
                    }
                } catch (_: NoSuchFileException) {
                    forgetLostFile(tally, entry, files)
                    continue
                } catch (failure: IOException) {
                    deferFile(
                        tally,
                        entry.id,
                        "file access failed (${failure.javaClass.simpleName})"
                    )
                    continue
                } catch (failure: SecurityException) {
                    deferFile(
                        tally,
                        entry.id,
                        "file access failed (${failure.javaClass.simpleName})"
                    )
                    continue
                }
                if (source == null) {
                    forgetLostFile(tally, entry, files)
                    continue
                }
                val size = source.size
                if (entry.kind == OutboxKind.RECORDING && entry.source == null &&
                    dao.stampLegacyRecording(entry.id, size, source.modifiedAt) == 0
                ) {
                    debug(TAG) { "Legacy recording row ${entry.id} changed before upload" }
                    continue
                }
                if (size > OutboxLimits.MAX_FILE_BYTES) {
                    deferRuledOn(
                        tally,
                        entry,
                        "${files.noun} of $size bytes is over the " +
                            "${OutboxLimits.MAX_FILE_BYTES / 1024 / 1024} MB any mobile device accepts",
                    )
                    continue
                }
                if (tally.bytesLeft <= 0) {
                    tally.moreWork = true
                    return Outcome.DONE
                }

                val payload = attempt {
                    files.decode(entry.payload, file, source)
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
                val body = client.fileBody(file, payload.metadata, files.fileKey, source)
                val wireSize = body.contentLength()
                val declared = tally.payloadLimit
                debug(TAG) {
                    "${files.noun} row ${entry.id}: $size bytes, " +
                        "$wireSize on the wire, cap $declared, " +
                        "attempt ${entry.attempts + 1}, ${tally.bytesLeft} budget left"
                }
                if (declared in 1..<wireSize) {
                    deferRuledOn(
                        tally,
                        entry,
                        "${files.noun} would send $wireSize bytes and the server declared a " +
                            "${declared / 1024} KB cap, so it is not sent",
                    )
                    continue
                }
                suspend fun send(verifiedBody: FileBody): UploadOutcome =
                    post(uploads.settings, tally) {
                        tally.bytesLeft -= wireSize
                        uploads.post(files.suffix, verifiedBody)
                    }

                val outcome = attempt {
                    val document = payload.document
                    if (document == null) {
                        send(body)
                    } else {
                        documentReader.withVerifiedContent(
                            file,
                            size,
                            entry.source?.sha256 ?: entry.recheckedSha256,
                            document,
                            pinRechecked = { digest ->
                                if (dao.pinRecheckedDocument(entry.id, digest) == 0) {
                                    throw IOException("Pending document changed before upload")
                                }
                            },
                        ) { input ->
                            send(body.fromVerifiedCopy(input))
                        }
                    }
                }.getOrElse { failure ->
                    if (failure is SQLiteException) {
                        val reason = "digest write failed (${failure.javaClass.simpleName})"
                        Log.w(TAG, reason)
                        tally.lastError = reason
                        return Outcome.RETRY
                    }
                    if (failure is IOException || failure is SecurityException) {
                        deferFile(
                            tally,
                            entry.id,
                            "file access failed (${failure.javaClass.simpleName})"
                        )
                        continue
                    }
                    return failBatch(tally, listOf(entry.id), failure)
                }
                if (outcome == null) {
                    forgetLostFile(tally, entry, files)
                    continue
                }
                debug(TAG) {
                    "${files.noun} row ${entry.id} answered ${outcome.diagnostic()}"
                }
                when (outcome) {
                    UploadOutcome.CredentialsUnavailable -> return credentialsUnavailable(tally)

                    is UploadOutcome.Success, is UploadOutcome.Duplicate -> {
                        val success = outcome as? UploadOutcome.Success
                        learn(tally, success?.limitBytes)
                        if (success != null &&
                            (success.skipped > 0 || 0 in success.skippedIndexes)
                        ) {
                            abandon(
                                tally,
                                files.kind,
                                listOf(entry.id),
                                "server skipped the ${files.noun}"
                            )
                            continue
                        }
                        tally.delivered = true
                        if (success == null || success.duplicates > 0) {
                            tally.note(tally.duplicates, files.kind, 1)
                        } else {
                            tally.note(tally.accepted, files.kind, 1)
                            payload.document?.let(tally.deliveredDocuments::add)
                        }
                        cleanup.acknowledge(entry.id, now())
                    }

                    is UploadOutcome.Rejected -> abandon(
                        tally,
                        files.kind,
                        listOf(entry.id),
                        "server refused the ${files.noun} (${outcome.code})",
                    )

                    is UploadOutcome.TooLarge -> {
                        learn(tally, outcome.limitBytes)
                        deferRuledOn(
                            tally,
                            entry,
                            "${files.noun} of $size bytes is ${outcome.describe()}",
                        )
                    }

                    is UploadOutcome.Retry -> {
                        if (outcome.scope == RetryScope.LOCAL_FILE) {
                            deferFile(tally, entry.id, outcome.reason)
                            continue
                        }
                        if (outcome.scope != RetryScope.ROW) {
                            return chargeTheLink(
                                tally,
                                listOf(entry.id),
                                outcome.reason,
                                outcome.scope == RetryScope.LINK,
                            )
                        }
                        deferRuledOn(tally, entry, outcome.reason)
                    }
                }
            }
        }
    }

    private suspend fun forgetLostFile(tally: DrainTally, entry: OutboxEntry, files: FileKind) {
        val folder = entry.filePath.orEmpty().loggableDirectory()
        val reason = "its original file is no longer available under $folder"
        val verb = if (files.kind == OutboxKind.RECORDING) {
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

    private suspend fun deferFile(tally: DrainTally, id: Long, reason: String) {
        Log.w(TAG, "Deferring file row $id: $reason")
        dao.deferFile(id, reason, deferral(0, tally.readyAt))
        tally.lastError = reason
    }

    private suspend fun deferRuledOn(tally: DrainTally, entry: OutboxEntry, reason: String) {
        Log.w(TAG, "Keeping ${entry.kind} row ${entry.id} the server would not take: $reason")
        dao.markFailed(
            listOf(entry.id),
            reason,
            serverFault = true,
            retryAfter = deferral(entry.attempts, tally.readyAt),
        )
        tally.lastError = reason
    }

    private suspend fun failBatch(tally: DrainTally, ids: List<Long>, cause: Throwable): Outcome =
        chargeTheLink(tally, ids, "upload failed (${cause.javaClass.simpleName})", linkDown = false)

    private suspend fun chargeTheLink(
        tally: DrainTally,
        ids: List<Long>,
        reason: String,
        linkDown: Boolean,
    ): Outcome {
        dao.markFailed(ids, reason)
        tally.lastError = reason
        tally.stopAfterKind = linkDown
        return Outcome.RETRY
    }

    companion object {
        const val LOCATION_BATCH = 200
        const val CALL_LOG_BATCH = 100
        const val FILE_BATCH = 3
        const val MAX_ATTEMPTS = 25

        const val MAX_REVIVALS = 3

        const val ATTEMPTS_AFTER_REVIVAL = MAX_ATTEMPTS - 1

        const val FILE_BYTES_PER_DRAIN = 64L * 1024 * 1024

        const val BATCHES_PER_DRAIN = 25

        const val ROW_BACKOFF_BASE_MILLIS = 60_000L
        const val ROW_BACKOFF_MAX_MILLIS = 60L * 60 * 1000
        private const val ROW_BACKOFF_DOUBLINGS = 6

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

        private val RECORDINGS = FileKind(
            OutboxKind.RECORDING,
            "recording",
            "recording",
            FileBody.AUDIO_KEY,
        ) { payload, _, _ ->
            FilePayload(
                WireJson.encodeToJsonElement(
                    WireJson.decodeFromString<RecordingMetadata>(payload)
                ).jsonObject
            )
        }

        private val DOCUMENTS = FileKind(
            OutboxKind.DOCUMENT,
            "document",
            "document",
            FileBody.FILE_KEY,
        ) { payload, file, stamp ->
            val document = QueuedDocument.decode(payload).fromInbox(file.path, stamp.modifiedAt)
            FilePayload(WireJson.encodeToJsonElement(document.metadata).jsonObject, document)
        }

        private val SCANS = FileKind(
            OutboxKind.SCAN,
            "document",
            "scan",
            FileBody.FILE_KEY,
        ) { payload, _, _ ->
            FilePayload(
                WireJson.encodeToJsonElement(
                    WireJson.decodeFromString<DocumentMetadata>(payload)
                ).jsonObject
            )
        }

        private val drainLock = Mutex()

        private val ownershipLock = Mutex()

        internal suspend fun <T> withQueueOwnership(action: suspend () -> T): T =
            ownershipLock.withLock { action() }
    }
}

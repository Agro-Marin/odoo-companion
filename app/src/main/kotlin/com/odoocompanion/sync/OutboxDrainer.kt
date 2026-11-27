package com.odoocompanion.sync

import android.util.Log
import com.odoocompanion.config.Settings
import com.odoocompanion.data.OutboxDao
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.data.OutboxLimits
import com.odoocompanion.data.QueuedFile
import com.odoocompanion.net.OdooClient
import com.odoocompanion.system.debug
import com.odoocompanion.whatsapp.DocumentReader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

internal const val DRAIN_TAG = "OutboxDrainer"

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
            Log.i(DRAIN_TAG, "Another drain holds the queue; leaving this pass to it")
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
            debug(DRAIN_TAG) { "Stopping before retention and upload: ${tally.lastError}" }
            return tally.report(Outcome.RETRY)
        }
        val outcome = drainUntilTheLinkFails(settings, tally)
        reviveWhatOnlyRanOutOfRetries(settings, tally, outcome)

        if (outcome == Outcome.DONE && !tally.moreWork) {
            tally.moreWork = anythingArrivedLate(settings)
        }
        debug(DRAIN_TAG) {
            "drain finished: $outcome, accepted=${tally.accepted}, skipped=${tally.skipped}, " +
                "duplicates=${tally.duplicates}, moreWork=${tally.moreWork}"
        }
        return tally.report(outcome)
    }

    private suspend fun drainUntilTheLinkFails(settings: Settings, tally: DrainTally): Outcome {
        val session = UploadSession(
            dao,
            client.uploads(settings),
            tally,
            now,
            withCurrentCredentials,
            ::persistLimit,
        )
        val kinds: List<suspend () -> Outcome> = listOf(
            { session.drainBatch(BatchRoute.LOCATIONS) },
            { session.drainBatch(BatchRoute.CALLS) },
            { session.drainFiles(FileRoute.DOCUMENTS, documentReader, cleanup) },
            { session.drainFiles(FileRoute.SCANS, documentReader, cleanup) },
            { session.drainFiles(FileRoute.RECORDINGS, documentReader, cleanup) },
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
                DRAIN_TAG,
                "Withdrew ${positions + withdrawn} row(s) of a previous enrolment, collected 90 days ago",
            )
        }
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
                DRAIN_TAG,
                "${tally.unreachable} row(s) failed again after $MAX_REVIVALS revivals; " +
                    "they are the payload's problem, not the link's, and stay set aside",
            )
        }

        tally.deadLettered = dao.markDead(OutboxKind.IRREPLACEABLE, MAX_ATTEMPTS, now())
        if (tally.deadLettered > 0) {
            Log.w(
                DRAIN_TAG,
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
                DRAIN_TAG,
                "Queueing ${tally.revived} row(s) that had only run out of retries " +
                    if (tally.delivered) "; delivery is working again" else " to test delivery",
            )
        }
    }

    private suspend fun persistLimit(limit: Long): Boolean = try {
        learnPayloadLimit(limit, now())
        true
    } catch (failure: IOException) {
        Log.w(DRAIN_TAG, "Could not save the payload limit (${failure.javaClass.simpleName})")
        false
    }

    private fun reportUnreadableSettings(tally: DrainTally, cause: Throwable): DrainReport {
        val reason = "device configuration unavailable (${cause.javaClass.simpleName})"
        Log.w(DRAIN_TAG, reason)
        tally.lastError = reason
        return tally.report(Outcome.RETRY)
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

        const val REVIVAL_PROBE = 1

        private val drainLock = Mutex()

        private val ownershipLock = Mutex()

        internal suspend fun <T> withQueueOwnership(action: suspend () -> T): T =
            ownershipLock.withLock { action() }
    }
}

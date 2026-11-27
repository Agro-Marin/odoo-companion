package com.odoocompanion.sync

import android.util.Log
import com.odoocompanion.data.DeadReason
import com.odoocompanion.data.OutboxEntry
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.net.CallLogUpload
import com.odoocompanion.net.CallRecord
import com.odoocompanion.net.LocationFix
import com.odoocompanion.net.LocationUpload
import com.odoocompanion.net.RetryScope
import com.odoocompanion.net.UploadOutcome
import com.odoocompanion.net.WireJson
import com.odoocompanion.sync.OutboxDrainer.Outcome
import com.odoocompanion.system.debug
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

internal class BatchRoute<T> private constructor(
    val kind: String,
    val suffix: String,
    val batchSize: Int,
    val item: KSerializer<T>,
    val encodeBatch: (List<T>) -> String,
) {
    companion object {
        val LOCATIONS = BatchRoute(
            OutboxKind.LOCATION,
            "location",
            OutboxDrainer.LOCATION_BATCH,
            LocationFix.serializer(),
        ) { WireJson.encodeToString(LocationUpload(it)) }

        val CALLS = BatchRoute(
            OutboxKind.CALL_LOG,
            "calllog",
            OutboxDrainer.CALL_LOG_BATCH,
            CallRecord.serializer(),
        ) { WireJson.encodeToString(CallLogUpload(it)) }
    }
}

internal suspend fun <T> UploadSession.drainBatch(wire: BatchRoute<T>): Outcome {
    var budget = OutboxDrainer.BATCHES_PER_DRAIN
    var size = wire.batchSize
    while (true) {
        if (budget-- <= 0) {
            tally.moreWork = true
            return Outcome.DONE
        }
        val entries = take(wire.kind, size) ?: return Outcome.RETRY
        debug(DRAIN_TAG) {
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
            post(wire.suffix, body)
        }.getOrElse { return failBatch(sent, it) }
        debug(DRAIN_TAG) {
            "${wire.suffix} with ${sent.size} row(s) answered ${outcome.diagnostic()}"
        }
        when (outcome) {
            UploadOutcome.CredentialsUnavailable -> return credentialsUnavailable()

            is UploadOutcome.Success -> {
                learn(outcome.limitBytes)
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
                    abandon(kind, refused, "server read it and could not store it")
                }
                if (outcome.skipped > refused.size) {
                    noteSkipped(kind, outcome.skipped - refused.size)
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
                abandon(kind, sent, "server refused the payload (${outcome.code})")
            }

            is UploadOutcome.TooLarge -> {
                learn(outcome.limitBytes)
                if (sent.size > 1) {
                    size = halved(sent.size, kind, outcome.describe())
                    continue
                }
                deferRuledOn(
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
                        sent,
                        outcome.reason,
                        outcome.scope == RetryScope.LINK
                    )
                }
                deferRuledOn(entries.single { it.id in sent }, outcome.reason)
                size = wire.batchSize
                continue
            }
        }
        if (entries.size < size) return Outcome.DONE
    }
}

private fun halved(size: Int, kind: String, why: String): Int {
    val narrowed = maxOf(1, size / 2)
    Log.w(DRAIN_TAG, "Narrowing the $kind batch from $size to $narrowed: $why")
    return narrowed
}

private fun UploadSession.noteSkipped(kind: String, count: Int) {
    tally.note(tally.skipped, kind, count)
    Log.w(DRAIN_TAG, "Server could not store $count $kind row(s); they are not retried")
}

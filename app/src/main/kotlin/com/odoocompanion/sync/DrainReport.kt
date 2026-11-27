package com.odoocompanion.sync

import com.odoocompanion.sync.OutboxDrainer.Outcome
import com.odoocompanion.whatsapp.QueuedDocument

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

internal class DrainTally(val readyAt: Long) {
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

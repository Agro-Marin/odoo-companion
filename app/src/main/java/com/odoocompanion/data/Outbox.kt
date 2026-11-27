package com.odoocompanion.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

object OutboxKind {
    const val LOCATION = "location"
    const val CALL_LOG = "calllog"
    const val RECORDING = "recording"

    const val DOCUMENT = "document"

    const val SCAN = "scan"

    val IRREPLACEABLE = setOf(CALL_LOG, RECORDING, DOCUMENT, SCAN)
}

@Entity(
    tableName = "outbox",
    indices = [
        Index(value = ["kind", "deadAt", "createdAt"]),
        Index(value = ["deadAt"]),
    ],
)
data class OutboxEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val payload: String,
    val filePath: String? = null,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
    val deadAt: Long? = null,
    val deadReason: String? = null,
    val revivals: Int = 0,
    val payloadVersion: Int = CURRENT_PAYLOAD_VERSION,
    val serverFault: Boolean = false,
    val retryAfter: Long = 0,
    val enrollment: String? = null,
    @Embedded(prefix = "source_") val source: FileStamp? = null,
    val recheckedSha256: String? = null,
)

const val CURRENT_PAYLOAD_VERSION = 1

object DeadReason {
    const val BUDGET = "budget"

    const val REFUSED = "refused"

    const val UNDECODABLE = "undecodable"

    const val ACKNOWLEDGED = "acknowledged"

    const val KEPT_ON_DISK = "kept_on_disk"

    const val GONE = "gone"

    const val WITHDRAWN = "withdrawn"
}

data class HeldDiscard(val positions: Int, val withdrawn: Int)

data class LocationDepth(val total: Int, val ready: Int, val oldestCreatedAt: Long?)

@Serializable
data class QueuedFileState(
    val filePath: String,
    val deadReason: String?,
    @Embedded(prefix = "source_") val source: FileStamp? = null,
    val createdAt: Long = 0,
    val recheckedSha256: String? = null,
) {
    internal fun matches(stamp: FileStamp?): Boolean {
        if (source == stamp) return true
        val digest = stamp?.sha256 ?: return false
        return recheckedSha256 == digest && stamp.matchesQueuedMetadata(source, createdAt)
    }
}

internal fun List<QueuedFileState>.rowFor(source: FileStamp?): QueuedFileState? {
    val matching = filter { it.matches(source) }
    if (source?.sha256 != null) {
        matching.lastOrNull { UploadState.fromDeadReason(it.deadReason) == UploadState.DELIVERED }
            ?.let { return it }
    }
    return matching.lastOrNull() ?: lastOrNull {
        it.source == null && source != null &&
            (source.sha256 == null || it.recheckedSha256 == null) &&
            source.matchesQueuedMetadata(null, it.createdAt)
    }
}

data class OutboxCounts(
    val positions: Int,
    val calls: Int,
    val recordings: Int,
    val documents: Int,
    val undeliverable: Int,
    val held: Int = 0,
    val heldFor: String? = null,
)

data class RecordingSource(
    val filePath: String,
    val createdAt: Long,
    @Embedded(prefix = "source_") val source: FileStamp?,
)

data class QueuedFile(
    val id: Long,
    val kind: String,
    val filePath: String,
    val createdAt: Long,
    val deadAt: Long,
    val deadReason: String?,
    @Embedded(prefix = "source_") val source: FileStamp?,
    val scanPayload: String?,
    val recheckedSha256: String? = null,
) {
    fun fileState() = QueuedFileState(filePath, deadReason, source, createdAt, recheckedSha256)

    val outcome: UploadState get() = UploadState.fromDeadReason(deadReason)
}

object OutboxLimits {
    const val MAX_FILE_BYTES = 36L * 1024 * 1024

    const val MAX_QUEUED_FIXES = 20_000

    const val TRIMMED_FIXES = 19_000

    const val DEAD_RETENTION_MILLIS = 90L * 24 * 60 * 60 * 1000
}

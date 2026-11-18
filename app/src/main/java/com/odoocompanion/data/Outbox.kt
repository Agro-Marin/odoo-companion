package com.odoocompanion.data

import androidx.annotation.VisibleForTesting
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

object OutboxKind {
    const val LOCATION = "location"
    const val CALL_LOG = "calllog"
    const val RECORDING = "recording"

    const val DOCUMENT = "document"

    // A document someone scanned or picked on the handset, in a copy the app
    // made for it: the outbox's to delete once delivered, like a recording.
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
    // Preserve the original history key when an older document first passes OCR on upload.
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

private const val OWNED = "(enrollment IS NULL OR enrollment = :enrollment)"

private const val HELD = "(enrollment IS NOT NULL AND enrollment IS NOT :enrollment)"

private const val REVIVABLE = "deadReason IN ('${DeadReason.BUDGET}', '${DeadReason.UNDECODABLE}')"

private const val PENDING = "(deadAt IS NULL OR $REVIVABLE)"

private const val HELD_FOR =
    "(SELECT enrollment FROM outbox WHERE $PENDING AND $HELD " +
        "ORDER BY createdAt DESC, id DESC LIMIT 1)"

private const val NOT_ACTIONABLE =
    "('${DeadReason.ACKNOWLEDGED}', '${DeadReason.KEPT_ON_DISK}', '${DeadReason.GONE}', '${DeadReason.WITHDRAWN}')"

private const val FILE_STATES =
    "SELECT filePath, createdAt, source_size, source_modifiedAt, source_sha256, recheckedSha256, " +
        "CASE WHEN deadAt IS NULL THEN NULL ELSE deadReason END AS deadReason FROM outbox"

private const val TERMINAL_FILES = """
    SELECT id, kind, filePath, createdAt, deadAt, deadReason,
           source_size, source_modifiedAt, source_sha256, recheckedSha256,
           CASE WHEN kind = '${OutboxKind.SCAN}' THEN payload END AS scanPayload
    FROM outbox
"""

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(entry: OutboxEntry): Long

    @Insert
    suspend fun insertAll(entries: List<OutboxEntry>)

    @Query(
        """
        SELECT * FROM outbox
        WHERE kind = :kind AND deadAt IS NULL AND retryAfter <= :now AND $OWNED
        ORDER BY createdAt ASC, id ASC LIMIT :limit
        """,
    )
    suspend fun take(
        kind: String,
        limit: Int,
        now: Long = Long.MAX_VALUE,
        enrollment: String? = null,
    ): List<OutboxEntry>

    @VisibleForTesting
    @Query(
        """
        SELECT COUNT(*) FROM outbox
        WHERE kind = :kind AND deadAt IS NULL AND retryAfter <= :now AND $OWNED
        """,
    )
    suspend fun countOf(kind: String, now: Long = Long.MAX_VALUE, enrollment: String? = null): Int

    @Query(
        """
        SELECT EXISTS(SELECT 1 FROM outbox
            WHERE kind IN (:kinds) AND deadAt IS NULL AND retryAfter <= :now AND $OWNED)
        """,
    )
    suspend fun hasReady(kinds: Set<String>, now: Long, enrollment: String?): Boolean

    // Retention counts every position. Cadence only needs the oldest eligible
    // position and whether the active enrollment has reached its threshold.
    @Query(
        """
        SELECT (SELECT COUNT(*) FROM outbox WHERE kind = '${OutboxKind.LOCATION}') AS total,
               COUNT(*) AS ready, MIN(createdAt) AS oldestCreatedAt
        FROM (
            SELECT createdAt FROM outbox
            WHERE kind = '${OutboxKind.LOCATION}' AND deadAt IS NULL AND retryAfter <= :now AND $OWNED
            ORDER BY createdAt LIMIT :threshold
        )
        """,
    )
    suspend fun locationDepth(enrollment: String?, threshold: Int, now: Long): LocationDepth

    @VisibleForTesting
    @Query("SELECT COUNT(*) FROM outbox WHERE deadAt IS NOT NULL")
    suspend fun countDead(): Int

    @Query(
        """
        SELECT
            COALESCE(SUM(deadAt IS NULL AND $OWNED AND kind = '${OutboxKind.LOCATION}'), 0)
                AS positions,
            COALESCE(SUM(deadAt IS NULL AND $OWNED AND kind = '${OutboxKind.CALL_LOG}'), 0)
                AS calls,
            COALESCE(SUM(deadAt IS NULL AND $OWNED AND kind = '${OutboxKind.RECORDING}'), 0)
                AS recordings,
            COALESCE(
                SUM(
                    deadAt IS NULL AND $OWNED
                        AND kind IN ('${OutboxKind.DOCUMENT}', '${OutboxKind.SCAN}')
                ),
                0
            ) AS documents,
            COALESCE(
                SUM(deadAt IS NOT NULL AND $OWNED AND deadReason NOT IN $NOT_ACTIONABLE),
                0
            ) AS undeliverable,
            (SELECT COUNT(*) FROM outbox WHERE $PENDING AND enrollment = $HELD_FOR) AS held,
            $HELD_FOR AS heldFor
        FROM outbox
        """,
    )
    fun counts(enrollment: String?): Flow<OutboxCounts>

    @Query("UPDATE outbox SET enrollment = :enrollment WHERE enrollment IS NULL")
    suspend fun claimUnstamped(enrollment: String): Int

    // A new destination has its own retry budget. Only a build that can read a
    // previously undecodable payload may revive it.
    @Query(
        """
        UPDATE outbox SET enrollment = :enrollment, attempts = 0, revivals = 0,
            retryAfter = 0, serverFault = 0,
            lastError = CASE WHEN deadReason = '${DeadReason.UNDECODABLE}' THEN lastError END,
            deadAt = CASE WHEN deadReason = '${DeadReason.UNDECODABLE}' THEN deadAt END,
            deadReason = CASE WHEN deadReason = '${DeadReason.UNDECODABLE}' THEN deadReason END
        WHERE enrollment = :from AND $HELD AND $PENDING
        """
    )
    suspend fun adoptHeld(enrollment: String, from: String): Int

    // Keep the retention check indexed by (kind, deadAt, createdAt). Combining the
    // live and legacy dead cases with OR scans every queued fix on each drain.
    @Query(
        """
        DELETE FROM outbox WHERE $HELD AND (:from IS NULL OR enrollment = :from)
            AND id IN (
                SELECT id FROM outbox WHERE kind = '${OutboxKind.LOCATION}'
                    AND deadAt IS NULL AND createdAt < :before
                UNION ALL
                SELECT id FROM outbox WHERE kind = '${OutboxKind.LOCATION}'
                    AND deadAt IS NOT NULL AND $REVIVABLE AND createdAt < :before
            )
        """,
    )
    suspend fun deleteHeldPositions(enrollment: String, from: String?, before: Long): Int

    @Query(
        """
        UPDATE outbox SET deadAt = :now, deadReason = '${DeadReason.WITHDRAWN}',
            lastError = 'collected for a previous enrolment'
        WHERE kind != '${OutboxKind.LOCATION}' AND $PENDING AND $HELD
            AND (:from IS NULL OR enrollment = :from)
            AND createdAt < :before
        """,
    )
    suspend fun withdrawHeld(enrollment: String, from: String?, now: Long, before: Long): Int

    @Transaction
    suspend fun discardHeld(
        enrollment: String,
        from: String?,
        now: Long,
        before: Long = Long.MAX_VALUE,
    ): HeldDiscard {
        val positions = deleteHeldPositions(enrollment, from, before)
        val withdrawn = withdrawHeld(enrollment, from, now, before)
        return HeldDiscard(positions, withdrawn)
    }

    @Query("DELETE FROM outbox WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query(
        """
        UPDATE outbox SET attempts = attempts + 1, lastError = :error, serverFault = :serverFault,
            retryAfter = :retryAfter
        WHERE id IN (:ids)
        """,
    )
    suspend fun markFailed(
        ids: List<Long>,
        error: String?,
        serverFault: Boolean = false,
        retryAfter: Long = 0,
    )

    @Query(
        """
        UPDATE outbox SET recheckedSha256 = :sha256
        WHERE id = :id AND kind = '${OutboxKind.DOCUMENT}' AND deadAt IS NULL
          AND source_sha256 IS NULL AND recheckedSha256 IS NULL
        """
    )
    suspend fun pinRecheckedDocument(id: Long, sha256: String): Int

    @Query(
        "UPDATE outbox SET retryAfter = :retryAfter, lastError = :reason WHERE id = :id AND deadAt IS NULL"
    )
    suspend fun deferFile(id: Long, reason: String, retryAfter: Long)

    @Query(
        """
        UPDATE outbox SET deadAt = :now, deadReason = '${DeadReason.REFUSED}'
        WHERE deadAt IS NULL AND attempts >= :maxAttempts AND serverFault
            AND revivals >= :maxRevivals AND kind IN (:keptKinds)
        """,
    )
    suspend fun markUnreachable(
        keptKinds: Set<String>,
        maxAttempts: Int,
        maxRevivals: Int,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE outbox SET deadAt = :now, deadReason = '${DeadReason.BUDGET}'
        WHERE deadAt IS NULL AND attempts >= :maxAttempts AND kind IN (:keptKinds)
        """,
    )
    suspend fun markDead(keptKinds: Set<String>, maxAttempts: Int, now: Long): Int

    @Query(
        """
        DELETE FROM outbox
        WHERE deadAt IS NULL AND attempts >= :maxAttempts AND kind NOT IN (:exceptKinds)
        """,
    )
    suspend fun deleteExhausted(exceptKinds: Set<String>, maxAttempts: Int): Int

    @Query(
        """
        UPDATE outbox
        SET deadAt = NULL, deadReason = NULL,
            attempts = :attempts, revivals = revivals + 1
        WHERE deadAt IS NOT NULL AND deadReason = '${DeadReason.BUDGET}' AND $OWNED
        """,
    )
    suspend fun reviveExhausted(attempts: Int, enrollment: String? = null): Int

    @Query(
        """
        UPDATE outbox
        SET deadAt = NULL, deadReason = NULL,
            attempts = :attempts, revivals = revivals + 1
        WHERE id IN (
            SELECT id FROM outbox
            WHERE deadAt IS NOT NULL AND deadReason = '${DeadReason.BUDGET}' AND $OWNED
            ORDER BY deadAt ASC, id ASC LIMIT :limit
        )
        """,
    )
    suspend fun reviveOldestExhausted(limit: Int, attempts: Int, enrollment: String? = null): Int

    @Query(
        """
        UPDATE outbox SET deadAt = :now, lastError = :reason, deadReason = :deadReason
        WHERE id IN (:ids)
        """,
    )
    suspend fun markDeadIds(
        ids: List<Long>,
        now: Long,
        reason: String?,
        deadReason: String = DeadReason.REFUSED,
    )

    @Query(
        """
        UPDATE outbox SET deadAt = NULL, deadReason = NULL, attempts = 0, revivals = 0
        WHERE deadAt IS NOT NULL AND deadReason = '${DeadReason.UNDECODABLE}'
        """,
    )
    suspend fun reviveUndecodable(): Int

    @Query(
        """
        $TERMINAL_FILES
        WHERE kind IN ('${OutboxKind.RECORDING}', '${OutboxKind.SCAN}', '${OutboxKind.DOCUMENT}')
          AND filePath IS NOT NULL AND deadAt IS NOT NULL AND id > :afterId
          AND (deadAt < :before OR deadReason = '${DeadReason.ACKNOWLEDGED}')
        ORDER BY id LIMIT :limit
        """,
    )
    suspend fun filesForCleanup(before: Long, afterId: Long, limit: Int): List<QueuedFile>

    @Query(
        """
        $TERMINAL_FILES
        WHERE id IN (:ids) AND filePath IS NOT NULL AND deadAt IS NOT NULL
        """,
    )
    suspend fun terminalFiles(ids: List<Long>): List<QueuedFile>

    @Transaction
    suspend fun acknowledgeFile(id: Long, now: Long): List<QueuedFile> {
        val ids = listOf(id)
        markDeadIds(ids, now, null, DeadReason.ACKNOWLEDGED)
        return terminalFiles(ids)
    }

    @Query(
        """
        UPDATE outbox SET deadAt = :now,
            lastError = CASE WHEN deadReason = '${DeadReason.ACKNOWLEDGED}'
                THEN 'uploaded, pending file cleanup' ELSE lastError END,
            deadReason = CASE WHEN deadReason = '${DeadReason.ACKNOWLEDGED}'
                THEN '${DeadReason.KEPT_ON_DISK}' ELSE deadReason END
        WHERE id IN (:ids)
        """,
    )
    suspend fun deferCleanup(ids: List<Long>, now: Long)

    // Archive history before entering this transaction. An upgrade or an operator
    // may have revived the row during that write. Hold the writer lock from the
    // final comparison through unlink and row deletion so revival cannot slip in.
    @Transaction
    suspend fun finishFileCleanup(
        expected: List<QueuedFile>,
        now: Long,
        removeFile: (QueuedFile) -> Boolean,
    ): Int {
        val candidates = expected.toHashSet()
        val current = terminalFiles(expected.map { it.id }).filter { it in candidates }
        val (removed, retained) = current.partition(removeFile)
        if (retained.isNotEmpty()) deferCleanup(retained.map { it.id }, now)
        if (removed.isNotEmpty()) delete(removed.map { it.id })
        return removed.size
    }

    @Query(
        """
        DELETE FROM outbox WHERE deadAt < :before AND (filePath IS NULL OR
            kind NOT IN ('${OutboxKind.RECORDING}', '${OutboxKind.SCAN}', '${OutboxKind.DOCUMENT}'))
        """,
    )
    suspend fun deleteExpiredWithoutFiles(before: Long): Int

    @Query(
        """
        DELETE FROM outbox WHERE kind = :kind AND id NOT IN (
            SELECT id FROM outbox WHERE kind = :kind ORDER BY createdAt DESC, id DESC LIMIT :keep
        )
        """,
    )
    suspend fun trimOldest(kind: String, keep: Int): Int

    @Query(
        "SELECT filePath, createdAt, source_size, source_modifiedAt, source_sha256 FROM outbox " +
            "WHERE kind = '${OutboxKind.RECORDING}' AND filePath IS NOT NULL",
    )
    suspend fun queuedRecordings(): List<RecordingSource>

    @Query(
        "UPDATE outbox SET source_size = :size, source_modifiedAt = :modifiedAt " +
            "WHERE id = :id AND source_size IS NULL AND deadAt IS NULL",
    )
    suspend fun stampLegacyRecording(id: Long, size: Long, modifiedAt: Long): Int

    @VisibleForTesting
    @Query("SELECT filePath FROM outbox WHERE kind = :kind AND filePath IS NOT NULL")
    suspend fun filesQueued(kind: String): List<String>

    @Query("$FILE_STATES WHERE kind = :kind AND filePath IS NOT NULL ORDER BY id")
    fun fileStates(kind: String): Flow<List<QueuedFileState>>

    @Query(
        "$FILE_STATES WHERE kind = '${OutboxKind.DOCUMENT}' AND filePath IN (:paths) ORDER BY id"
    )
    suspend fun documentStates(paths: List<String>): List<QueuedFileState>

    @Transaction
    suspend fun insertMissingDocuments(entries: List<OutboxEntry>): Int {
        val unique = entries.distinctBy { it.filePath to it.source }
        val existing = documentStates(unique.map { requireNotNull(it.filePath) }.distinct())
            .groupBy { it.filePath }
        val fresh = unique.filter { existing[it.filePath]?.rowFor(it.source) == null }
        if (fresh.isNotEmpty()) insertAll(fresh)
        return fresh.size
    }
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
    // Old histories may lack a digest too. Keep their collection-time fallback,
    // but never use a timestamp to override two known, different digests.
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

@Database(entities = [OutboxEntry::class], version = 10, exportSchema = true)
abstract class CompanionDatabase : RoomDatabase() {
    abstract fun outbox(): OutboxDao
}

val COMPANION_MIGRATIONS: Array<Migration> = arrayOf(
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_outbox_kind_createdAt` " +
                    "ON `outbox` (`kind`, `createdAt`)",
            )
        }
    },
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `outbox` ADD COLUMN `deadAt` INTEGER DEFAULT NULL")
        }
    },
    object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `outbox` ADD COLUMN `deadReason` TEXT DEFAULT NULL")

            db.execSQL(
                "UPDATE `outbox` SET `deadReason` = '${DeadReason.REFUSED}' " +
                    "WHERE `deadAt` IS NOT NULL",
            )
            db.execSQL("DROP INDEX IF EXISTS `index_outbox_kind_createdAt`")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_outbox_kind_deadAt_createdAt` " +
                    "ON `outbox` (`kind`, `deadAt`, `createdAt`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_outbox_deadAt` ON `outbox` (`deadAt`)",
            )
        }
    },
    object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `outbox` ADD COLUMN `revivals` INTEGER NOT NULL DEFAULT 0",
            )

            db.execSQL(
                "ALTER TABLE `outbox` ADD COLUMN `payloadVersion` INTEGER NOT NULL DEFAULT 1",
            )
        }
    },
    object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `outbox` ADD COLUMN `serverFault` INTEGER NOT NULL DEFAULT 0",
            )
        }
    },
    object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `outbox` ADD COLUMN `retryAfter` INTEGER NOT NULL DEFAULT 0",
            )
        }
    },
    object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `outbox` ADD COLUMN `enrollment` TEXT DEFAULT NULL")
        }
    },
    object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE outbox ADD COLUMN source_size INTEGER")
            db.execSQL("ALTER TABLE outbox ADD COLUMN source_modifiedAt INTEGER")
        }
    },
    object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE outbox ADD COLUMN source_sha256 TEXT")
            db.execSQL("ALTER TABLE outbox ADD COLUMN recheckedSha256 TEXT")
        }
    },
)

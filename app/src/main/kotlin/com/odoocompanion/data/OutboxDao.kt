package com.odoocompanion.data

import androidx.annotation.VisibleForTesting
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

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

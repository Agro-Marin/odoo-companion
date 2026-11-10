package com.odoocompanion.data

import androidx.annotation.VisibleForTesting
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

object OutboxKind {
    const val LOCATION = "location"
    const val CALL_LOG = "calllog"
    const val RECORDING = "recording"

    // A document the handset received, in a file it does not own: WhatsApp's.
    const val DOCUMENT = "document"

    val IRREPLACEABLE = setOf(CALL_LOG, RECORDING, DOCUMENT)
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
    // The earliest moment this row may be sent again. A row the server has
    // ruled on -- a 500 from an ingest that raises on it -- waits here on its
    // own, so the queue around it keeps moving instead of inheriting its
    // backoff. Zero, the default, means no wait.
    val retryAfter: Long = 0,
    // Settings.enrollment when the row was queued: a row goes only to the
    // server and device record it was collected for. Null -- a row queued
    // before this column, or while the settings could not be read -- belongs to
    // whatever enrolment is current.
    val enrollment: String? = null,
)

const val CURRENT_PAYLOAD_VERSION = 1

object DeadReason {
    const val BUDGET = "budget"

    const val REFUSED = "refused"

    const val UNDECODABLE = "undecodable"

    // Delivered, and the audio could not be removed from the dialer's folder.
    // The row is kept so the harvest does not find the file and queue it
    // again; the purge takes the file with it when it can.
    const val KEPT_ON_DISK = "kept_on_disk"

    // A document whose file left WhatsApp's folder before it was sent. Kept, not
    // deleted: the row is the WhatsApp card's only memory of the document, and
    // without it the card read the empty queue as delivered. Nothing an
    // operator does brings the file back, so it is not undeliverable either.
    const val GONE = "gone"

    // Collected for an enrolment the handset no longer has, and discarded there
    // rather than sent to the new one: by hand, or once collected more than
    // DEAD_RETENTION_MILLIS ago. Nothing an operator does makes it deliverable.
    const val WITHDRAWN = "withdrawn"
}

// The rows that belong to an enrolment: its own, and the unstamped.
private const val OWNED = "(enrollment IS NULL OR enrollment = :enrollment)"

// The rows collected for any other enrolment.
private const val HELD = "(enrollment IS NOT NULL AND enrollment IS NOT :enrollment)"

// Rows that would still go, were they this enrolment's: live, or set aside only
// for what a delivery (budget) or a newer build (undecodable) undoes.
private const val PENDING =
    "(deadAt IS NULL OR deadReason IN ('${DeadReason.BUDGET}', '${DeadReason.UNDECODABLE}'))"

// The previous enrolment the held band is about: the newest held row's.
private const val HELD_FOR =
    "(SELECT enrollment FROM outbox WHERE $PENDING AND $HELD " +
        "ORDER BY createdAt DESC, id DESC LIMIT 1)"

// Set aside, and not for anything an operator can fix on the device record.
private const val NOT_ACTIONABLE =
    "('${DeadReason.KEPT_ON_DISK}', '${DeadReason.GONE}', '${DeadReason.WITHDRAWN}')"

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(entry: OutboxEntry): Long

    @Insert
    suspend fun insertAll(entries: List<OutboxEntry>)

    // Every reader of the live queue names the enrolment it reads for, so a
    // row collected for another one is never taken, counted or waited on.
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

    // `now` narrows to rows that are due. The default counts every live row,
    // which is what the status screen wants: a deferred row is still queued.
    @Query(
        """
        SELECT COUNT(*) FROM outbox
        WHERE kind = :kind AND deadAt IS NULL AND retryAfter <= :now AND $OWNED
        """,
    )
    suspend fun countOf(kind: String, now: Long = Long.MAX_VALUE, enrollment: String? = null): Int

    @Query(
        """
        SELECT COUNT(*) AS queued, MIN(createdAt) AS oldestCreatedAt FROM outbox
        WHERE kind = :kind AND deadAt IS NULL AND retryAfter <= :now AND $OWNED
        """,
    )
    suspend fun depthOf(
        kind: String,
        now: Long = Long.MAX_VALUE,
        enrollment: String? = null,
    ): QueueDepth

    @VisibleForTesting
    @Query("SELECT COUNT(*) FROM outbox WHERE deadAt IS NOT NULL")
    suspend fun countDead(): Int

    // What the status screen shows, re-read whenever the table changes: a
    // screen that read its counts only when a setting changed showed the queue
    // as it was when the screen last happened to redraw. Undeliverable leaves
    // out kept_on_disk (delivered, kept so the harvest does not queue its file
    // again), gone and withdrawn: no operator action helps any of them -- and
    // another enrolment's rows, which no fix to this device sends. Held rows are
    // counted apart, one previous enrolment at a time, the newest first: the
    // band's buttons act on the enrolment it names and on no other.
    @Query(
        """
        SELECT
            COALESCE(SUM(deadAt IS NULL AND $OWNED AND kind = '${OutboxKind.LOCATION}'), 0)
                AS positions,
            COALESCE(SUM(deadAt IS NULL AND $OWNED AND kind = '${OutboxKind.CALL_LOG}'), 0)
                AS calls,
            COALESCE(SUM(deadAt IS NULL AND $OWNED AND kind = '${OutboxKind.RECORDING}'), 0)
                AS recordings,
            COALESCE(SUM(deadAt IS NULL AND $OWNED AND kind = '${OutboxKind.DOCUMENT}'), 0)
                AS documents,
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

    // A null row is the current enrolment's: an upgrade's backlog, queued before
    // rows carried their enrolment, and a row queued while the settings could
    // not be read. Claimed by the enrolment being left, as it is left -- read as
    // the next one's, a backlog that waited out an unreachable server went to
    // the server that replaced it, under the new device.
    @Query("UPDATE outbox SET enrollment = :enrollment WHERE enrollment IS NULL")
    suspend fun claimUnstamped(enrollment: String): Int

    // The three ends of a held row. Sent here: an operator says the new
    // enrolment is the same place under another name (a moved domain, a
    // renamed device record). Discarded: positions go, as positions do; the
    // rest is set aside as withdrawn, so a recording's audio leaves with the
    // 90-day purge and a document's row stays the WhatsApp card's memory.
    // One previous enrolment, the one the band named: the rows of an older one
    // are another organisation's until someone says otherwise.
    @Query("UPDATE outbox SET enrollment = :enrollment WHERE enrollment = :from")
    suspend fun adoptHeld(enrollment: String, from: String): Int

    @Query(
        """
        DELETE FROM outbox
        WHERE kind = '${OutboxKind.LOCATION}' AND deadAt IS NULL AND $HELD
            AND (:from IS NULL OR enrollment = :from) AND createdAt < :before
        """,
    )
    suspend fun deleteHeldPositions(
        enrollment: String,
        from: String? = null,
        before: Long = Long.MAX_VALUE,
    ): Int

    @Query(
        """
        UPDATE outbox SET deadAt = :now, deadReason = '${DeadReason.WITHDRAWN}',
            lastError = 'collected for a previous enrolment'
        WHERE $PENDING AND $HELD AND (:from IS NULL OR enrollment = :from)
            AND createdAt < :before
        """,
    )
    suspend fun withdrawHeld(
        enrollment: String,
        from: String?,
        now: Long,
        before: Long = Long.MAX_VALUE,
    ): Int

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
        SELECT id, filePath FROM outbox
        WHERE deadAt IS NOT NULL AND deadAt < :before AND filePath IS NOT NULL
          AND kind = '${OutboxKind.RECORDING}'
        """,
    )
    suspend fun deadFilesBefore(before: Long): List<QueuedFile>

    @Query("UPDATE outbox SET deadAt = :now WHERE id IN (:ids)")
    suspend fun restampDead(ids: List<Long>, now: Long)

    @Query("DELETE FROM outbox WHERE deadAt IS NOT NULL AND deadAt < :before")
    suspend fun deleteDeadBefore(before: Long): Int

    @Query(
        """
        DELETE FROM outbox WHERE kind = :kind AND id NOT IN (
            SELECT id FROM outbox WHERE kind = :kind ORDER BY createdAt DESC, id DESC LIMIT :keep
        )
        """,
    )
    suspend fun trimOldest(kind: String, keep: Int): Int

    @Query("SELECT filePath FROM outbox WHERE kind = :kind AND filePath IS NOT NULL")
    suspend fun filesQueued(kind: String): List<String>

    // Each file of a kind still in the queue, and why it was set aside, if it was.
    @Query(
        "SELECT filePath, CASE WHEN deadAt IS NULL THEN NULL ELSE deadReason END AS deadReason " +
            "FROM outbox WHERE kind = :kind AND filePath IS NOT NULL",
    )
    fun fileStates(kind: String): Flow<List<QueuedFileState>>
}

data class QueueDepth(val queued: Int, val oldestCreatedAt: Long?)

// deadReason is null while the row is live.
data class QueuedFileState(val filePath: String, val deadReason: String?)

data class OutboxCounts(
    val positions: Int,
    val calls: Int,
    val recordings: Int,
    val documents: Int,
    val undeliverable: Int,
    val held: Int = 0,
    val heldFor: String? = null,
)

data class QueuedFile(val id: Long, val filePath: String)

object OutboxLimits {
    const val MAX_QUEUED_FIXES = 20_000

    const val TRIMMED_FIXES = 19_000

    const val DEAD_RETENTION_MILLIS = 90L * 24 * 60 * 60 * 1000
}

@Database(entities = [OutboxEntry::class], version = 8, exportSchema = true)
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
            // Zero for every existing row: whatever it was waiting on before,
            // it is due now, and the next drain decides afresh.
            db.execSQL(
                "ALTER TABLE `outbox` ADD COLUMN `retryAfter` INTEGER NOT NULL DEFAULT 0",
            )
        }
    },
    object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Null for every existing row: queued before rows were stamped, it
            // belongs to the enrolment the handset has when it is drained.
            db.execSQL("ALTER TABLE `outbox` ADD COLUMN `enrollment` TEXT DEFAULT NULL")
        }
    },
)

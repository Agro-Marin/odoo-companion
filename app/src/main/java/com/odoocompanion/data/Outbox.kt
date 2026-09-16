package com.odoocompanion.data

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

object OutboxKind {
    const val LOCATION = "location"
    const val CALL_LOG = "calllog"
    const val RECORDING = "recording"

    val IRREPLACEABLE = setOf(CALL_LOG, RECORDING)
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
}

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(entry: OutboxEntry): Long

    @Insert
    suspend fun insertAll(entries: List<OutboxEntry>)

    @Query(
        """
        SELECT * FROM outbox WHERE kind = :kind AND deadAt IS NULL
        ORDER BY createdAt ASC, id ASC LIMIT :limit
        """,
    )
    suspend fun take(kind: String, limit: Int): List<OutboxEntry>

    @Query("SELECT COUNT(*) FROM outbox WHERE kind = :kind AND deadAt IS NULL")
    suspend fun countOf(kind: String): Int

    @Query(
        """
        SELECT COUNT(*) AS queued, MIN(createdAt) AS oldestCreatedAt FROM outbox
        WHERE kind = :kind AND deadAt IS NULL
        """,
    )
    suspend fun depthOf(kind: String): QueueDepth

    @Query("SELECT COUNT(*) FROM outbox WHERE deadAt IS NOT NULL")
    suspend fun countDead(): Int

    @Query("DELETE FROM outbox WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query(
        """
        UPDATE outbox SET attempts = attempts + 1, lastError = :error, serverFault = :serverFault
        WHERE id IN (:ids)
        """,
    )
    suspend fun markFailed(ids: List<Long>, error: String?, serverFault: Boolean = false)

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
        WHERE deadAt IS NOT NULL AND deadReason = '${DeadReason.BUDGET}'
        """,
    )
    suspend fun reviveExhausted(attempts: Int): Int

    @Query(
        """
        UPDATE outbox
        SET deadAt = NULL, deadReason = NULL,
            attempts = :attempts, revivals = revivals + 1
        WHERE id IN (
            SELECT id FROM outbox
            WHERE deadAt IS NOT NULL AND deadReason = '${DeadReason.BUDGET}'
            ORDER BY deadAt ASC, id ASC LIMIT :limit
        )
        """,
    )
    suspend fun reviveOldestExhausted(limit: Int, attempts: Int): Int

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
        SELECT filePath FROM outbox
        WHERE deadAt IS NOT NULL AND deadAt < :before AND filePath IS NOT NULL
        """,
    )
    suspend fun deadFilesBefore(before: Long): List<String>

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

    @Query("SELECT COUNT(*) FROM outbox WHERE kind = :kind")
    suspend fun countIncludingDead(kind: String): Int

    @Query("SELECT filePath FROM outbox WHERE kind = :kind AND filePath IS NOT NULL")
    suspend fun filesQueued(kind: String): List<String>

    @Query(
        """
        SELECT MIN(createdAt) FROM outbox WHERE kind = :kind AND deadAt IS NULL
        """,
    )
    suspend fun oldestCreatedAt(kind: String): Long?
}

data class QueueDepth(val queued: Int, val oldestCreatedAt: Long?)

object OutboxLimits {
    const val MAX_QUEUED_FIXES = 20_000

    const val TRIMMED_FIXES = 19_000

    const val DEAD_RETENTION_MILLIS = 90L * 24 * 60 * 60 * 1000
}

@Database(entities = [OutboxEntry::class], version = 6, exportSchema = true)
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
)

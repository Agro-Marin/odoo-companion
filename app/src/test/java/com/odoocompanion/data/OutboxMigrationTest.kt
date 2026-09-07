package com.odoocompanion.data

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OutboxMigrationTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private val createV1 =
        """
        CREATE TABLE IF NOT EXISTS `outbox` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `kind` TEXT NOT NULL, `payload` TEXT NOT NULL, `filePath` TEXT,
            `createdAt` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, `lastError` TEXT)
        """

    private fun openV1(): SupportSQLiteDatabase = openV1AtPath(null)

    private fun openV1AtPath(path: String?): SupportSQLiteDatabase {
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) = db.execSQL(createV1)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                Unit
        }
        return FrameworkSQLiteOpenHelperFactory()
            .create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name(path)
                    .callback(callback)
                    .build(),
            )
            .writableDatabase
    }

    private fun indexesOn(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA index_list(`$table`)").use { cursor ->
            buildList {
                val name = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) add(cursor.getString(name))
            }
        }

    @Test
    fun `migrating to v2 adds the index and keeps every queued row`() {
        val db = openV1()
        db.execSQL(
            "INSERT INTO outbox (kind, payload, createdAt, attempts) VALUES " +
                "('calllog', '{\"number\":\"+52\"}', 10, 3), ('location', '{}', 20, 0)",
        )

        COMPANION_MIGRATIONS.single { it.startVersion == 1 && it.endVersion == 2 }.migrate(db)

        assertTrue("index_outbox_kind_createdAt" in indexesOn(db, "outbox"))
        db.query("SELECT kind, attempts FROM outbox ORDER BY createdAt").use { cursor ->
            assertEquals(2, cursor.count)
            cursor.moveToFirst()
            assertEquals("calllog", cursor.getString(0))
            assertEquals(3, cursor.getInt(1))
        }
        db.close()
    }

    @Test
    fun `the migration is idempotent`() {
        val db = openV1()
        val migration = COMPANION_MIGRATIONS.single { it.startVersion == 1 && it.endVersion == 2 }

        migration.migrate(db)
        migration.migrate(db)

        assertEquals(1, indexesOn(db, "outbox").count { it == "index_outbox_kind_createdAt" })
        db.close()
    }

    @Test
    fun `a v1 database on disk opens after upgrading, with its rows intact`() = runTest {
        val file = File.createTempFile("companion", ".db").apply { delete() }
        openV1AtPath(file.absolutePath).use { db ->
            db.execSQL(createV1)
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS room_master_table " +
                    "(id INTEGER PRIMARY KEY, identity_hash TEXT)",
            )
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
                arrayOf(V1_IDENTITY_HASH),
            )
            db.execSQL(
                "INSERT INTO outbox (kind, payload, createdAt, attempts) " +
                    "VALUES ('calllog', '{\"number\":\"+525512345678\"}', 99, 2)",
            )
            db.version = 1
        }

        val database = Room.databaseBuilder(
            context,
            CompanionDatabase::class.java,
            file.absolutePath,
        ).addMigrations(*COMPANION_MIGRATIONS).build()

        val surviving = database.outbox().take(OutboxKind.CALL_LOG, 10)
        assertEquals(1, surviving.size)
        assertEquals(2, surviving.first().attempts)
        database.close()
        file.delete()
    }

    private companion object {
        const val V1_IDENTITY_HASH = "6e486cab9fc830486deabc7a5e5fed18"
    }

    @Test
    fun `the drain queries use the index instead of scanning`() = runTest {
        val database = Room.inMemoryDatabaseBuilder(context, CompanionDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val plans = listOf(
            "SELECT * FROM outbox WHERE kind = 'location' AND deadAt IS NULL " +
                "ORDER BY createdAt ASC LIMIT 200",
            "SELECT COUNT(*) FROM outbox WHERE kind = 'location' AND deadAt IS NULL",
            "SELECT * FROM outbox WHERE kind = 'recording' AND filePath = '/x' LIMIT 1",

            "SELECT COUNT(*) FROM outbox WHERE deadAt IS NOT NULL",
            "SELECT * FROM outbox WHERE deadAt IS NOT NULL AND deadAt < 1",
        ).map { sql ->
            database.openHelper.writableDatabase.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.columnCount - 1))
                }.joinToString(" / ")
            }
        }

        plans.forEach { plan ->
            assertTrue("still scanning: $plan", plan.contains("SEARCH"))
            assertTrue("still sorting: $plan", !plan.contains("TEMP B-TREE"))
        }
        database.close()
    }

    @Test
    fun `migrating to v3 adds deadAt and leaves existing rows retryable`() {
        val db = openV1()
        db.execSQL(
            "INSERT INTO outbox (kind, payload, createdAt, attempts) VALUES " +
                "('calllog', '{\"number\":\"+52\"}', 10, 3)",
        )
        COMPANION_MIGRATIONS.single { it.startVersion == 1 && it.endVersion == 2 }.migrate(db)

        COMPANION_MIGRATIONS.single { it.startVersion == 2 && it.endVersion == 3 }.migrate(db)

        db.query("SELECT kind, attempts, deadAt FROM outbox").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("calllog", cursor.getString(0))
            assertEquals(3, cursor.getInt(1))

            assertTrue(cursor.isNull(2))
        }
        db.close()
    }

    @Test
    fun `migrating to v4 records why each dead row is dead`() {
        val db = openV1()
        db.execSQL(
            "INSERT INTO outbox (kind, payload, createdAt, attempts) VALUES " +
                "('calllog', '{\"number\":\"+52\"}', 10, 30), " +
                "('location', '{}', 20, 0)",
        )
        migrate(db, 1, 2)
        migrate(db, 2, 3)
        db.execSQL("UPDATE outbox SET deadAt = 999 WHERE kind = 'calllog'")

        migrate(db, 3, 4)

        db.query("SELECT kind, deadAt, deadReason FROM outbox ORDER BY createdAt").use { cursor ->
            assertEquals(2, cursor.count)
            cursor.moveToFirst()
            assertEquals("calllog", cursor.getString(0))
            assertEquals(999, cursor.getInt(1))
            assertEquals(DeadReason.REFUSED, cursor.getString(2))
            cursor.moveToNext()

            assertTrue(cursor.isNull(2))
        }
        val indexes = indexesOn(db, "outbox")
        assertTrue("index_outbox_kind_deadAt_createdAt" in indexes)
        assertTrue("index_outbox_deadAt" in indexes)

        assertTrue("index_outbox_kind_createdAt" !in indexes)
        db.close()
    }

    @Test
    fun `the v4 migration is idempotent`() {
        val db = openV1()
        migrate(db, 1, 2)
        migrate(db, 2, 3)

        migrate(db, 3, 4)
        val second = runCatching { migrate(db, 3, 4) }

        assertTrue(second.isFailure)
        assertEquals(1, indexesOn(db, "outbox").count { it == "index_outbox_deadAt" })
        db.close()
    }

    @Test
    fun `migrating to v5 bounds revivals and marks the queue format of existing rows`() {
        val db = openV1()
        db.execSQL(
            "INSERT INTO outbox (kind, payload, createdAt, attempts) VALUES " +
                "('calllog', '{\"number\":\"+52\"}', 10, 30)",
        )
        migrate(db, 1, 2)
        migrate(db, 2, 3)
        migrate(db, 3, 4)

        migrate(db, 4, 5)

        db.query("SELECT revivals, payloadVersion FROM outbox").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(
                "a row that predates the counter has spent no revival",
                0,
                cursor.getInt(0)
            )
            assertEquals(
                "and was written by a build whose format is the one we still read",
                CURRENT_PAYLOAD_VERSION,
                cursor.getInt(1),
            )
        }
        db.close()
    }

    @Test
    fun `the v5 migration is idempotent`() {
        val db = openV1()
        migrate(db, 1, 2)
        migrate(db, 2, 3)
        migrate(db, 3, 4)

        migrate(db, 4, 5)
        val second = runCatching { migrate(db, 4, 5) }

        assertTrue("adding a column twice must not pass silently", second.isFailure)
        db.close()
    }

    private fun migrate(db: SupportSQLiteDatabase, from: Int, to: Int) = COMPANION_MIGRATIONS
        .single { it.startVersion == from && it.endVersion == to }
        .migrate(db)
}

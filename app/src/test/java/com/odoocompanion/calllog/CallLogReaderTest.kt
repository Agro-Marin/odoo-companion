package com.odoocompanion.calllog

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CallLog
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

internal data class Call(
    val number: String?,
    val type: Int = 1,
    val date: Long,
    val duration: Long = 30,
    val cachedName: String? = null,
    val modified: Long = date,
    // Zero means "let the provider assign one", which it does by position, the
    // way an insert would. A test that cares about ids sets them itself.
    val id: Long = 0,
)

internal class FakeCallLogProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        lastSelection = selection
        if (returnsNull) return null

        val argument = selectionArgs?.firstOrNull()?.toLong() ?: 0
        var rows = withIds().filter { row ->
            when {
                selection == null -> true
                selection.startsWith(CallLog.Calls._ID) -> row.id > argument
                selection.startsWith(CallLog.Calls.LAST_MODIFIED) -> row.modified <= argument
                else -> true
            }
        }
        rows = if (sortOrder?.contains("DESC") == true) {
            rows.sortedByDescending { it.id }
        } else {
            rows.sortedBy { it.id }
        }
        uri.getQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY)?.toIntOrNull()?.let {
            rows = rows.take(it)
        }

        val columns = projection ?: emptyArray()
        val cursor = MatrixCursor(columns)
        rows.forEach { row ->
            val values: List<Any?> = columns.map { column ->
                when (column) {
                    CallLog.Calls._ID -> row.id
                    CallLog.Calls.NUMBER -> row.number
                    CallLog.Calls.TYPE -> row.type
                    CallLog.Calls.DATE -> row.date
                    CallLog.Calls.DURATION -> row.duration
                    CallLog.Calls.CACHED_NAME -> row.cachedName
                    CallLog.Calls.LAST_MODIFIED -> row.modified
                    else -> null
                }
            }
            cursor.addRow(values)
        }
        return cursor
    }

    private fun withIds(): List<Call> = calls.mapIndexed { index, call ->
        if (call.id == 0L) call.copy(id = index + 1L) else call
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    companion object {
        var calls: List<Call> = emptyList()
        var returnsNull = false
        var lastSelection: String? = null
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CallLogReaderTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        FakeCallLogProvider.calls = emptyList()
        FakeCallLogProvider.returnsNull = false
        ShadowContentResolver.registerProviderInternal(
            CallLog.AUTHORITY,
            FakeCallLogProvider().apply { onCreate() },
        )
    }

    private fun reader() = CallLogReader(context.contentResolver) { FIXED_NOW }

    private fun payloadsOf(batch: CallLogBatch) =
        batch.entries.map { Json.parseToJsonElement(it.payload).jsonObject }

    @Test
    fun `a call is mapped to the payload the server documents`() {
        FakeCallLogProvider.calls = listOf(
            Call(
                number = "+525512345678",
                type = 2,
                date = 1_700_000_000_000,
                duration = 42,
                cachedName = "Ana",
            ),
        )

        val payload = payloadsOf(reader().readSince(0)).single()

        assertEquals("+525512345678", payload.getValue("number").jsonPrimitive.content)
        assertEquals("outgoing", payload.getValue("direction").jsonPrimitive.content)
        assertEquals(
            1_700_000_000_000,
            payload.getValue("timestamp").jsonPrimitive.content.toLong(),
        )
        assertEquals(42, payload.getValue("duration").jsonPrimitive.content.toInt())
        assertEquals("Ana", payload.getValue("contact_name").jsonPrimitive.content)
    }

    @Test
    fun `a call with no cached name simply omits it`() {
        FakeCallLogProvider.calls = listOf(Call(number = "+52551", date = 10))

        assertNull(payloadsOf(reader().readSince(0)).single()["contact_name"])
    }

    @Test
    fun `the cursor lands on the newest row read`() {
        FakeCallLogProvider.calls = listOf(
            Call(number = "+1", date = 10),
            Call(number = "+2", date = 30),
            Call(number = "+3", date = 20),
        )

        assertEquals(3, reader().readSince(0).cursor)
    }

    @Test
    fun `an empty log leaves the cursor where it was`() {
        assertEquals(555, reader().readSince(555).cursor)
    }

    @Test
    fun `only calls after the cursor are read`() {
        FakeCallLogProvider.calls = listOf(
            Call(number = "+old", date = 10),
            Call(number = "+new", date = 20),
        )

        val batch = reader().readSince(1)

        assertEquals(
            listOf("+new"),
            payloadsOf(batch).map { it.getValue("number").jsonPrimitive.content },
        )
        assertEquals("${CallLog.Calls._ID} > ?", FakeCallLogProvider.lastSelection)
    }

    @Test
    fun `a blank number is skipped without stalling the cursor`() {
        FakeCallLogProvider.calls = listOf(
            Call(number = "", date = 10),
            Call(number = null, date = 20),
        )

        val batch = reader().readSince(0)

        assertEquals(0, batch.entries.size)
        assertEquals(2, batch.cursor)
    }

    @Test
    fun `a long history is read in bounded batches that resume where they stopped`() {
        FakeCallLogProvider.calls = (1..250).map { Call(number = "+$it", date = it.toLong()) }

        val first = reader().readSince(0, limit = 100)
        val second = reader().readSince(first.cursor, limit = 100)
        val third = reader().readSince(second.cursor, limit = 100)

        assertEquals(100, first.entries.size)
        assertEquals(100, second.entries.size)
        assertEquals(50, third.entries.size)
        assertEquals(250, third.cursor)

        val numbers = listOf(first, second, third)
            .flatMap { batch ->
                payloadsOf(batch).map { it.getValue("number").jsonPrimitive.content }
            }
        assertEquals(250, numbers.size)
        assertEquals(250, numbers.toSet().size)
    }

    // The rule this replaces let a batch overrun its limit to avoid cutting
    // through rows that shared a timestamp, because a cursor set mid-run would
    // skip the rest of it. Ids do not tie, so the limit is now exactly a limit.
    @Test
    fun `a batch stops at its limit, since ids cannot tie across the boundary`() {
        FakeCallLogProvider.calls = listOf(
            Call(number = "+1", date = 10),
            Call(number = "+2", date = 20),
            Call(number = "+3", date = 20),
            Call(number = "+4", date = 20),
        )

        val first = reader().readSince(0, limit = 2)
        val second = reader().readSince(first.cursor, limit = 2)

        assertEquals(2, first.entries.size)
        assertEquals(2, second.entries.size)
        assertEquals(4, second.cursor)
    }

    @Test
    fun `a provider that returns nothing is not an error`() {
        FakeCallLogProvider.returnsNull = true

        val batch = reader().readSince(77)

        assertEquals(0, batch.entries.size)
        assertEquals(77, batch.cursor)
    }

    // Proven against the LAST_MODIFIED cursor before this change: it read
    // nothing at all, for ever, and the worker reported success.
    @Test
    fun `rows the provider never stamped are still read`() {
        FakeCallLogProvider.calls = listOf(
            Call(number = "+52migrated", date = 1_000, modified = 0),
            Call(number = "+52alsomigrated", date = 2_000, modified = 0),
        )

        val batch = reader().readSince(0)

        assertEquals(2, batch.entries.size)
        assertEquals(2, batch.cursor)
    }

    // Proven against the LAST_MODIFIED cursor before this change: the call
    // stamped after the correction landed below the cursor and was lost.
    @Test
    fun `a call stamped after the clock stepped backwards is still read`() {
        FakeCallLogProvider.calls = listOf(
            Call(number = "+52before", date = 5_000, modified = 5_000),
        )
        val first = reader().readSince(0)

        FakeCallLogProvider.calls = FakeCallLogProvider.calls +
            Call(number = "+52after", date = 4_000, modified = 4_000)

        val second = reader().readSince(first.cursor)

        assertEquals(
            listOf("+52after"),
            payloadsOf(second).map { it.getValue("number").jsonPrimitive.content },
        )
    }

    @Test
    fun `the id cursor is carried across from the moment the old one reached`() {
        FakeCallLogProvider.calls = listOf(
            Call(number = "+52sent", date = 1_000, modified = 1_000),
            Call(number = "+52alsosent", date = 2_000, modified = 2_000),
            Call(number = "+52unsent", date = 3_000, modified = 3_000),
        )

        assertEquals(2, reader().idAt(2_000))
    }

    @Test
    fun `an untouched timestamp cursor carries across to the beginning`() {
        FakeCallLogProvider.calls = listOf(Call(number = "+52", date = 1_000))

        assertEquals(0, reader().idAt(0))
    }

    @Test
    fun `the newest id is what says whether the log was replaced`() {
        FakeCallLogProvider.calls = listOf(
            Call(number = "+1", date = 10),
            Call(number = "+2", date = 20),
        )

        assertEquals(2L, reader().newestId())

        FakeCallLogProvider.calls = emptyList()

        assertNull(reader().newestId())
    }

    private companion object {
        const val FIXED_NOW = 1_800_000_000_000
    }

    @Test
    fun `a call answered on a linked device is still reported as incoming`() {
        FakeCallLogProvider.calls = listOf(
            Call(number = "+525512345678", type = 7, date = 1L, duration = 5, cachedName = null),
        )

        val payload = payloadsOf(reader().readSince(0)).single()

        assertEquals("incoming", payload.getValue("direction").jsonPrimitive.content)
    }

    @Test
    fun `every android call type this build knows maps to a word the endpoint stores`() {
        val stored = setOf("incoming", "outgoing", "missed", "voicemail", "rejected", "blocked")
        FakeCallLogProvider.calls = (1..7).map { type ->
            Call(
                number = "+5255000000$type",
                type = type,
                date = type.toLong(),
                duration = 1,
                cachedName = null,
            )
        }

        val directions = payloadsOf(reader().readSince(0))
            .map { it.getValue("direction").jsonPrimitive.content }

        assertEquals(7, directions.size)
        assertEquals(emptyList<String>(), directions.filterNot { it in stored })
    }

    @Test
    fun `an unknown android type is passed through rather than invented`() {
        FakeCallLogProvider.calls = listOf(
            Call(number = "+525512345678", type = 99, date = 1L, duration = 5, cachedName = null),
        )

        val payload = payloadsOf(reader().readSince(0)).single()

        assertEquals("99", payload.getValue("direction").jsonPrimitive.content)
    }

    @Test
    fun `rows without a number still count against the bound`() {
        FakeCallLogProvider.calls = (0 until 12).map {
            Call(number = "", type = CallLog.Calls.INCOMING_TYPE, date = 1_000L + it)
        }

        val batch = reader().readSince(0L, limit = 5)

        assertTrue("a filtered row is still read work", batch.moreWaiting)
        assertEquals(0, batch.entries.size)
        assertEquals(5L, batch.cursor)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CallLogCursorTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        FakeCallLogProvider.calls = emptyList()
        FakeCallLogProvider.returnsNull = false
        ShadowContentResolver.registerProviderInternal(
            CallLog.AUTHORITY,
            FakeCallLogProvider().apply { onCreate() },
        )
    }

    @Test
    fun `a long call that ends after a shorter one was synced is read on the next pass`() {
        // Android inserts a call-log row when the call ENDS, stamped with its START time.
        // A: starts at 100, still in progress. B: call-waiting at 200, missed, row written.
        FakeCallLogProvider.calls = listOf(Call(number = "+B", date = 200))
        val reader = CallLogReader(context.contentResolver) { 1_000 }
        val first = reader.readSince(0)
        assertEquals(1, first.entries.size)

        // A ends now; its row lands with DATE 100, behind a cursor kept on DATE.
        FakeCallLogProvider.calls = listOf(
            Call(number = "+B", date = 200),
            Call(number = "+A", date = 100, modified = 700),
        )
        val second = reader.readSince(first.cursor)

        val payload = Json.parseToJsonElement(second.entries.single().payload).jsonObject
        assertEquals("+A", payload.getValue("number").jsonPrimitive.content)
        assertEquals(100, payload.getValue("timestamp").jsonPrimitive.content.toLong())
        // The cursor is the row's id now, not the moment it was stamped. What
        // this test pins is unchanged: the late row is read on the next pass.
        assertEquals(2, second.cursor)
    }
}

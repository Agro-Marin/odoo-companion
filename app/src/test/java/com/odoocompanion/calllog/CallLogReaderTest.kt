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
        val since = selectionArgs?.firstOrNull()?.toLong() ?: 0
        val cursor = MatrixCursor(projection ?: emptyArray())
        calls.filter { it.modified > since }.sortedBy { it.modified }.forEach {
            cursor.addRow(
                arrayOf<Any?>(it.number, it.type, it.date, it.duration, it.cachedName, it.modified),
            )
        }
        return cursor
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

        assertEquals(30, reader().readSince(0).cursor)
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

        val batch = reader().readSince(10)

        assertEquals(
            listOf("+new"),
            payloadsOf(batch).map { it.getValue("number").jsonPrimitive.content },
        )
        assertEquals("${CallLog.Calls.LAST_MODIFIED} > ?", FakeCallLogProvider.lastSelection)
    }

    @Test
    fun `a blank number is skipped without stalling the cursor`() {
        FakeCallLogProvider.calls = listOf(
            Call(number = "", date = 10),
            Call(number = null, date = 20),
        )

        val batch = reader().readSince(0)

        assertEquals(0, batch.entries.size)
        assertEquals(20, batch.cursor)
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

    @Test
    fun `a batch is never cut through calls sharing a millisecond`() {
        FakeCallLogProvider.calls = listOf(
            Call(number = "+1", date = 10),
            Call(number = "+2", date = 20),
            Call(number = "+3", date = 20),
            Call(number = "+4", date = 20),
        )

        val first = reader().readSince(0, limit = 2)
        val second = reader().readSince(first.cursor, limit = 2)

        assertEquals(4, first.entries.size)
        assertEquals(0, second.entries.size)
    }

    @Test
    fun `a provider that returns nothing is not an error`() {
        FakeCallLogProvider.returnsNull = true

        val batch = reader().readSince(77)

        assertEquals(0, batch.entries.size)
        assertEquals(77, batch.cursor)
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
        assertEquals(1_004L, batch.cursor)
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
        assertEquals(700, second.cursor)
    }
}

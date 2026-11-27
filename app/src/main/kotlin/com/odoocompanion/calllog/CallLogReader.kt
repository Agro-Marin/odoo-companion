package com.odoocompanion.calllog

import android.content.ContentResolver
import android.database.Cursor
import android.provider.CallLog
import com.odoocompanion.data.OutboxEntry
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.net.CallRecord
import com.odoocompanion.net.WireJson
import kotlinx.serialization.encodeToString

data class CallLogBatch(
    val entries: List<OutboxEntry>,
    val cursor: Long,
    val moreWaiting: Boolean = false,
)

class CallLogReader(
    private val resolver: ContentResolver,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun readSince(sinceId: Long, limit: Int = DEFAULT_LIMIT): CallLogBatch {
        val cursor = resolver.query(
            CallLog.Calls.CONTENT_URI.buildUpon()
                .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, (limit + 1).toString())
                .build(),
            PROJECTION,
            "${CallLog.Calls._ID} > ?",
            arrayOf(sinceId.toString()),
            "${CallLog.Calls._ID} ASC",
        ) ?: return CallLogBatch(emptyList(), sinceId)
        return cursor.use { read(it, sinceId, limit) }
    }

    fun newestId(): Long? {
        val cursor = resolver.query(
            CallLog.Calls.CONTENT_URI.buildUpon()
                .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, "1")
                .build(),
            arrayOf(CallLog.Calls._ID),
            null,
            null,
            "${CallLog.Calls._ID} DESC",
        ) ?: return null
        return cursor.use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    fun idAt(moment: Long): Long {
        if (moment <= 0) return 0
        val cursor = resolver.query(
            CallLog.Calls.CONTENT_URI.buildUpon()
                .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, "1")
                .build(),
            arrayOf(CallLog.Calls._ID),
            "${CallLog.Calls.LAST_MODIFIED} <= ?",
            arrayOf(moment.toString()),
            "${CallLog.Calls._ID} DESC",
        ) ?: return 0
        return cursor.use { if (it.moveToFirst()) it.getLong(0) else 0 }
    }

    private fun read(cursor: Cursor, sinceId: Long, limit: Int): CallLogBatch {
        val idIndex = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
        val numberIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
        val typeIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
        val dateIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
        val durationIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
        val nameIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
        val presentationIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER_PRESENTATION)

        val entries = mutableListOf<OutboxEntry>()
        var reached = sinceId
        var scanned = 0
        var stoppedAtLimit = false
        while (cursor.moveToNext()) {
            if (scanned >= limit) {
                stoppedAtLimit = true
                break
            }
            scanned++
            reached = cursor.getLong(idIndex)
            val stored = cursor.getString(numberIndex).orEmpty()
            val withheld = withheldAs(cursor.getInt(presentationIndex)) ?: PLACEHOLDERS[stored]
            val number = if (withheld == null) stored else ""
            if (number.isBlank() && withheld == null) continue
            entries += OutboxEntry(
                kind = OutboxKind.CALL_LOG,
                payload = WireJson.encodeToString(
                    CallRecord(
                        number = number,
                        presentation = withheld,
                        direction = CallDirection.of(cursor.getInt(typeIndex)),
                        timestamp = cursor.getLong(dateIndex),
                        duration = cursor.getLong(durationIndex),
                        contactName = cursor.getString(nameIndex)?.takeIf { it.isNotBlank() },
                    ),
                ),
                createdAt = now(),
            )
        }
        return CallLogBatch(entries, reached, moreWaiting = stoppedAtLimit)
    }

    companion object {
        const val DEFAULT_LIMIT = 1_000

        fun withheldAs(presentation: Int): String? = when (presentation) {
            CallLog.Calls.PRESENTATION_RESTRICTED -> "restricted"
            CallLog.Calls.PRESENTATION_PAYPHONE -> "payphone"
            CallLog.Calls.PRESENTATION_UNKNOWN, PRESENTATION_UNAVAILABLE -> "unknown"
            else -> null
        }

        private const val PRESENTATION_UNAVAILABLE = 5

        private val PLACEHOLDERS =
            mapOf("-1" to "unknown", "-2" to "restricted", "-3" to "payphone")

        private val PROJECTION = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.NUMBER_PRESENTATION,
        )
    }
}

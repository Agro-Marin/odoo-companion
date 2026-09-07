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
    fun readSince(since: Long, limit: Int = DEFAULT_LIMIT): CallLogBatch {
        val cursor = resolver.query(
            CallLog.Calls.CONTENT_URI,
            PROJECTION,
            "${CallLog.Calls.DATE} > ?",
            arrayOf(since.toString()),
            "${CallLog.Calls.DATE} ASC",
        ) ?: return CallLogBatch(emptyList(), since)
        return cursor.use { read(it, since, limit) }
    }

    private fun read(cursor: Cursor, since: Long, limit: Int): CallLogBatch {
        val numberIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
        val typeIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
        val dateIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
        val durationIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
        val nameIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)

        val entries = mutableListOf<OutboxEntry>()
        var reached = since
        var scanned = 0
        var stoppedAtLimit = false
        while (cursor.moveToNext()) {
            val date = cursor.getLong(dateIndex)

            if (scanned >= limit && date != reached) {
                stoppedAtLimit = true
                break
            }
            scanned++
            reached = date
            val number = cursor.getString(numberIndex).orEmpty()
            if (number.isBlank()) continue
            entries += OutboxEntry(
                kind = OutboxKind.CALL_LOG,
                payload = WireJson.encodeToString(
                    CallRecord(
                        number = number,
                        direction = CallDirection.of(cursor.getInt(typeIndex)),
                        timestamp = date,
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

        private val PROJECTION = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.CACHED_NAME,
        )
    }
}

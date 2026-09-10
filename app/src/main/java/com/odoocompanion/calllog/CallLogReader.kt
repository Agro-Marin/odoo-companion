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
    // The identity of every row this batch queued, for the caller to remember.
    // A cursor alone cannot answer "have I sent this one", and reading behind
    // the cursor -- which is the point of the lookback -- makes that the
    // question that matters.
    val queuedKeys: Set<String> = emptySet(),
)

class CallLogReader(
    private val resolver: ContentResolver,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun readSince(
        since: Long,
        limit: Int = DEFAULT_LIMIT,
        alreadyQueued: Set<String> = emptySet(),
    ): CallLogBatch {
        val cursor = resolver.query(
            CallLog.Calls.CONTENT_URI,
            PROJECTION,
            "${CallLog.Calls.DATE} > ?",
            arrayOf(since.toString()),
            "${CallLog.Calls.DATE} ASC",
        ) ?: return CallLogBatch(emptyList(), since)
        return cursor.use { read(it, since, limit, alreadyQueued) }
    }

    private fun read(
        cursor: Cursor,
        since: Long,
        limit: Int,
        alreadyQueued: Set<String>,
    ): CallLogBatch {
        val numberIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
        val typeIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
        val dateIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
        val durationIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
        val nameIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)

        val entries = mutableListOf<OutboxEntry>()
        val queued = mutableSetOf<String>()
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
            val direction = CallDirection.of(cursor.getInt(typeIndex))
            val key = keyOf(date, direction, number)
            if (key in alreadyQueued) continue
            queued += key
            entries += OutboxEntry(
                kind = OutboxKind.CALL_LOG,
                payload = WireJson.encodeToString(
                    CallRecord(
                        number = number,
                        direction = direction,
                        timestamp = date,
                        duration = cursor.getLong(durationIndex),
                        contactName = cursor.getString(nameIndex)?.takeIf { it.isNotBlank() },
                    ),
                ),
                createdAt = now(),
            )
        }
        return CallLogBatch(entries, reached, stoppedAtLimit, queued)
    }

    companion object {
        const val DEFAULT_LIMIT = 1_000

        // CallLog.Calls.DATE is when a call STARTED, but the provider writes
        // the row when it ENDS. A short call placed during a long one is
        // therefore written first and carries the later date, so a cursor set
        // to "the newest date I have read" moves past a call that has not been
        // written yet. That call is then never read again, which is silent and
        // permanent loss -- and call waiting and a second SIM make it ordinary
        // rather than exotic. Reading this far behind the cursor covers any
        // call longer than the lookback; six hours is well past a plausible
        // one.
        const val LATE_WRITE_LOOKBACK_MILLIS = 6L * 60 * 60 * 1000

        fun keyOf(date: Long, direction: String, number: String): String =
            "$date|$direction|$number"

        // Pruning is done against the cursor rather than the wall clock: both
        // are call-log time, so the two cannot drift apart.
        fun forget(keys: Set<String>, before: Long): Set<String> =
            keys.filterTo(mutableSetOf()) { dateOf(it) > before }

        private fun dateOf(key: String): Long = key.substringBefore('|').toLongOrNull() ?: 0

        private val PROJECTION = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.CACHED_NAME,
        )
    }
}

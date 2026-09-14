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
    // The cursor is a row id, not a moment. Every timestamp this table carries
    // comes from a clock the app does not control, and a row stamped at or
    // below the cursor is invisible for ever: DATE lost a call that started
    // before a shorter one and was written after it, and LAST_MODIFIED, which
    // fixed that, still loses one stamped after the clock is corrected
    // backwards and reads none at all from a provider that leaves the column
    // at its default. An id is assigned by the insert itself.
    fun readSince(sinceId: Long, limit: Int = DEFAULT_LIMIT): CallLogBatch {
        val cursor = resolver.query(
            CallLog.Calls.CONTENT_URI,
            PROJECTION,
            "${CallLog.Calls._ID} > ?",
            arrayOf(sinceId.toString()),
            "${CallLog.Calls._ID} ASC",
        ) ?: return CallLogBatch(emptyList(), sinceId)
        return cursor.use { read(it, sinceId, limit) }
    }

    // The id of the newest row the provider holds, or null when it holds none.
    // Read only to answer a question the batch cannot: whether a pass that
    // returned nothing did so because there is nothing new, or because the call
    // log was cleared and its ids restarted below the cursor.
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

    // The id to start from when the id cursor has never been written, given
    // whatever the timestamp cursor had reached. Rows at or below the moment
    // already delivered are skipped; everything after it is read again and
    // comes back as a duplicate, which is a delivery. A stored value left by a
    // build that cursored on DATE rather than LAST_MODIFIED matches fewer rows
    // and so seeds lower, which re-reads more -- the safe direction.
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

        val entries = mutableListOf<OutboxEntry>()
        var reached = sinceId
        var scanned = 0
        var stoppedAtLimit = false
        while (cursor.moveToNext()) {
            // Ids are unique, so unlike a timestamp there is no run of rows
            // sharing one value that a batch boundary could cut through.
            if (scanned >= limit) {
                stoppedAtLimit = true
                break
            }
            scanned++
            reached = cursor.getLong(idIndex)
            val number = cursor.getString(numberIndex).orEmpty()
            if (number.isBlank()) continue
            entries += OutboxEntry(
                kind = OutboxKind.CALL_LOG,
                payload = WireJson.encodeToString(
                    CallRecord(
                        number = number,
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

        private val PROJECTION = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.CACHED_NAME,
        )
    }
}

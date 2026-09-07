package com.odoocompanion.location

import com.odoocompanion.data.OutboxDao
import com.odoocompanion.data.OutboxEntry
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.data.OutboxLimits
import com.odoocompanion.data.QueueDepth
import com.odoocompanion.net.LocationFix
import com.odoocompanion.net.WireJson
import com.odoocompanion.sync.UploadCadence
import kotlinx.serialization.encodeToString

class LocationQueue(
    private val dao: OutboxDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var askedAt: Long? = null

    suspend fun record(fixes: List<LocationFix>, windowSeconds: Long): Boolean {
        if (fixes.isEmpty()) return false
        val queuedAt = now()
        dao.insertAll(
            fixes.map { fix ->
                OutboxEntry(
                    kind = OutboxKind.LOCATION,
                    payload = WireJson.encodeToString(fix),
                    createdAt = queuedAt,
                )
            },
        )
        val depth = trimToBound()
        val at = now()
        val due = UploadCadence.dueNow(
            queued = depth.queued,
            oldestCreatedAt = depth.oldestCreatedAt,
            windowSeconds = windowSeconds,
            now = at,
        )
        if (!due) return false
        val last = askedAt
        if (last != null && at - last < windowSeconds * 1_000) return false
        askedAt = at
        return true
    }

    private suspend fun trimToBound(): QueueDepth {
        val depth = dao.depthOf(OutboxKind.LOCATION)
        if (depth.queued <= OutboxLimits.MAX_QUEUED_FIXES) return depth
        dao.trimOldest(OutboxKind.LOCATION, OutboxLimits.TRIMMED_FIXES)
        return dao.depthOf(OutboxKind.LOCATION)
    }
}

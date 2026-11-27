package com.odoocompanion.location

import com.odoocompanion.config.DEFAULT_MIN_MOVE_METRES
import com.odoocompanion.data.LocationDepth
import com.odoocompanion.data.OutboxDao
import com.odoocompanion.data.OutboxEntry
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.data.OutboxLimits
import com.odoocompanion.net.LocationFix
import com.odoocompanion.net.WireJson
import com.odoocompanion.sync.UploadCadence
import com.odoocompanion.system.debug
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class LocationQueue(
    private val dao: OutboxDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val recording = Mutex()
    private var owner: String? = null
    private var askedAt: Long? = null

    private var lastQueued: LocationFix? = null

    suspend fun record(
        fixes: List<LocationFix>,
        windowSeconds: Long,
        minMoveMetres: Long = DEFAULT_MIN_MOVE_METRES,
        enrollment: String? = null,
    ): Boolean = recording.withLock {
        if (fixes.isEmpty()) return false
        val sameOwner = enrollment == owner
        var previous = lastQueued.takeIf { sameOwner }
        val moving = fixes.filter { fix ->
            if (fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0) {
                return@filter false
            }
            val keep = worthKeeping(previous, fix, minMoveMetres)
            if (keep) previous = fix
            keep
        }
        if (moving.isEmpty()) {
            debug(TAG) { "no usable movement in ${fixes.size} fix(es) at $minMoveMetres m" }
            return false
        }
        val queuedAt = now()
        dao.insertAll(
            moving.map { fix ->
                OutboxEntry(
                    kind = OutboxKind.LOCATION,
                    payload = WireJson.encodeToString(fix),
                    createdAt = queuedAt,
                    enrollment = enrollment,
                )
            },
        )
        if (!sameOwner) askedAt = null
        owner = enrollment
        lastQueued = previous
        val at = now()
        val depth = trimToBound(enrollment, at)
        val due = UploadCadence.dueNow(
            queued = depth.ready,
            oldestCreatedAt = depth.oldestCreatedAt,
            windowSeconds = windowSeconds,
            now = at,
        )
        val last = askedAt
        val asked = due && (last == null || at < last || at - last >= windowSeconds * 1_000)
        debug(TAG) {
            "kept ${moving.size} of ${fixes.size} fix(es), ${depth.total} stored, " +
                "due=$due, asking=$asked"
        }
        if (asked) askedAt = at
        asked
    }

    private fun worthKeeping(last: LocationFix?, fix: LocationFix, minMoveMetres: Long): Boolean =
        minMoveMetres <= 0 || last == null || fix.timestamp < last.timestamp ||
            fix.timestamp - last.timestamp >= HEARTBEAT_MILLIS ||
            metresBetween(last, fix) >= minMoveMetres

    private suspend fun trimToBound(enrollment: String?, at: Long): LocationDepth {
        val depth = dao.locationDepth(enrollment, UploadCadence.BATCH_THRESHOLD, at)
        if (depth.total <= OutboxLimits.MAX_QUEUED_FIXES) return depth
        dao.trimOldest(OutboxKind.LOCATION, OutboxLimits.TRIMMED_FIXES)
        return dao.locationDepth(enrollment, UploadCadence.BATCH_THRESHOLD, at)
    }

    companion object {
        const val HEARTBEAT_MILLIS = 10L * 60 * 1000

        private const val EARTH_RADIUS_METRES = 6_371_000.0

        private const val TAG = "LocationQueue"

        internal fun metresBetween(from: LocationFix, to: LocationFix): Double {
            val dLat = Math.toRadians(to.latitude - from.latitude)
            val dLon = Math.toRadians(to.longitude - from.longitude)
            val lat1 = Math.toRadians(from.latitude)
            val lat2 = Math.toRadians(to.latitude)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                sin(dLon / 2) * sin(dLon / 2) * cos(lat1) * cos(lat2)
            return 2 * EARTH_RADIUS_METRES * asin(min(1.0, sqrt(a)))
        }
    }
}

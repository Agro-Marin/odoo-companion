package com.odoocompanion.location

import com.odoocompanion.config.DEFAULT_MIN_MOVE_METRES
import com.odoocompanion.data.OutboxDao
import com.odoocompanion.data.OutboxEntry
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.data.OutboxLimits
import com.odoocompanion.data.QueueDepth
import com.odoocompanion.net.LocationFix
import com.odoocompanion.net.WireJson
import com.odoocompanion.sync.UploadCadence
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
    private var askedAt: Long? = null

    // The last fix actually written, which is what a new one is measured
    // against. In memory only: a service restart queues one extra fix, which is
    // cheaper than a column and self-correcting.
    private var lastQueued: LocationFix? = null

    suspend fun record(
        fixes: List<LocationFix>,
        windowSeconds: Long,
        minMoveMetres: Long = DEFAULT_MIN_MOVE_METRES,
    ): Boolean {
        if (fixes.isEmpty()) return false
        val moving = fixes.filter { worthKeeping(it, minMoveMetres) }
        if (moving.isEmpty()) return false
        val queuedAt = now()
        dao.insertAll(
            moving.map { fix ->
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

    // A phone standing still still reports: at the default interval that is
    // 1,440 fixes a day saying the same thing, and the row, the upload and the
    // storage in Odoo are all paid for. What stops this from being a silent
    // phone instead of a quiet one is the heartbeat -- a stationary device is
    // still heard from, just not once a minute.
    private fun worthKeeping(fix: LocationFix, minMoveMetres: Long): Boolean {
        if (minMoveMetres <= 0) return true
        val last = lastQueued
        if (last == null || fix.timestamp - last.timestamp >= HEARTBEAT_MILLIS) {
            lastQueued = fix
            return true
        }
        if (metresBetween(last, fix) < minMoveMetres) return false
        lastQueued = fix
        return true
    }

    private suspend fun trimToBound(): QueueDepth {
        val depth = dao.depthOf(OutboxKind.LOCATION)
        if (depth.queued <= OutboxLimits.MAX_QUEUED_FIXES) return depth
        dao.trimOldest(OutboxKind.LOCATION, OutboxLimits.TRIMMED_FIXES)
        return dao.depthOf(OutboxKind.LOCATION)
    }

    companion object {
        // Long enough that a parked phone is quiet, short enough that one which
        // died looks different from one which is merely still.
        const val HEARTBEAT_MILLIS = 10L * 60 * 1000

        private const val EARTH_RADIUS_METRES = 6_371_000.0

        // Haversine, written out rather than taken from android.location, so it
        // is arithmetic a unit test can exercise without a framework under it.
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

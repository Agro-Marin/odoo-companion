package com.odoocompanion.softphone

import android.telecom.DisconnectCause
import org.linphone.core.Call
import org.linphone.core.Reason

enum class Offer {
    REPORT,

    DECLINE_BUSY,
}

data class Ended<C : Any>(val connection: C?)

class SoftphoneCallBook<C : Any> {
    private var reported: String? = null
    private val connections = LinkedHashMap<String, C>()

    val idle: Boolean get() = reported == null && connections.isEmpty()

    val current: C? get() = connections.values.lastOrNull()

    fun offer(callId: String): Offer = if (idle) {
        reported = callId
        Offer.REPORT
    } else {
        Offer.DECLINE_BUSY
    }

    fun attachIncoming(callId: String?, connection: C): Boolean {
        val id = reported ?: return false
        if (callId != null && callId != id) return false
        reported = null
        connections[id] = connection
        return true
    }

    fun refuseIncoming(callId: String?): String? {
        val refused = reported ?: return null
        if (callId != null && callId != refused) return null
        reported = null
        return refused
    }

    fun attachOutgoing(callId: String, connection: C) {
        connections[callId] = connection
    }

    fun connectionOf(callId: String): C? = connections[callId]

    fun callOf(connection: C): String? =
        connections.entries.firstOrNull { it.value === connection }?.key

    fun ended(callId: String): Ended<C>? {
        if (callId == reported) {
            reported = null
            return Ended(null)
        }
        val connection = connections.remove(callId) ?: return null
        return Ended(connection)
    }

    fun clear(): List<C> {
        reported = null
        return connections.values.toList().also { connections.clear() }
    }

    companion object {
        private val RINGING = setOf(
            Call.State.Idle,
            Call.State.IncomingReceived,
            Call.State.PushIncomingReceived,
            Call.State.IncomingEarlyMedia,
        )

        private val OVER = setOf(Call.State.End, Call.State.Error, Call.State.Released)

        fun onTheLine(states: Collection<Call.State?>): Boolean =
            states.any { it != null && it !in RINGING && it !in OVER }

        fun disconnectCause(state: Call.State?, status: Call.Status?, reason: Reason?): Int = when {
            status == Call.Status.AcceptedElsewhere -> DisconnectCause.REMOTE
            status == Call.Status.DeclinedElsewhere -> DisconnectCause.REJECTED
            status == Call.Status.Missed -> DisconnectCause.MISSED
            reason == Reason.Busy || reason == Reason.Declined -> DisconnectCause.REMOTE
            state == Call.State.Error -> DisconnectCause.LOCAL
            else -> DisconnectCause.REMOTE
        }

        val VOIP_CAUSES = setOf(
            DisconnectCause.LOCAL,
            DisconnectCause.REMOTE,
            DisconnectCause.MISSED,
            DisconnectCause.REJECTED,
        )
    }
}

package com.odoocompanion.softphone

import android.telecom.DisconnectCause
import org.linphone.core.Call
import org.linphone.core.Reason

/** What to do with an INVITE that has just arrived. */
enum class Offer {
    /** Ring: hand it to Telecom, which creates its connection. */
    REPORT,

    /** A call is already up or ringing: decline busy, for the phone system's voicemail. */
    DECLINE_BUSY,
}

/** A call that ended, and the connection it owned, if Telecom had created one yet. */
data class Ended<C : Any>(val connection: C?)

/**
 * Which SIP call owns which Telecom connection, keyed by the SIP Call-ID. One
 * call at a time, as a company line has: a second INVITE is declined busy, and
 * only the call a connection belongs to may end it. Without the key, a second
 * INVITE declined busy reached the end-of-call branch with the first call's
 * connection in hand and hung that one up.
 *
 * C is the connection type, so this is checkable without Telecom or the SIP stack.
 */
class SoftphoneCallBook<C : Any> {
    // reported to Telecom, whose connection has not been created yet
    private var reported: String? = null
    private val connections = LinkedHashMap<String, C>()

    val idle: Boolean get() = reported == null && connections.isEmpty()

    val current: C? get() = connections.values.lastOrNull()

    /** An INVITE arrived; covers the gap between reporting a call and Telecom creating it. */
    fun offer(callId: String): Offer = if (idle) {
        reported = callId
        Offer.REPORT
    } else {
        Offer.DECLINE_BUSY
    }

    /**
     * Telecom created the connection for a reported call; false when that call is
     * gone. A request that lost the Call-ID can only be for the one call reported.
     */
    fun attachIncoming(callId: String?, connection: C): Boolean {
        val id = reported ?: return false
        if (callId != null && callId != id) return false
        reported = null
        connections[id] = connection
        return true
    }

    /** Telecom refused the reported call: the one the SIP stack must now decline, if any. */
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

    /** The SIP stack ended a call: null when it is not one of ours, such as a call declined busy. */
    fun ended(callId: String): Ended<C>? {
        if (callId == reported) {
            reported = null
            return Ended(null)
        }
        val connection = connections.remove(callId) ?: return null
        return Ended(connection)
    }

    /** Every connection still open, for a SIP stack going away under them. */
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

        /** Whether any call has the microphone open: answered, or dialled out. */
        fun onTheLine(states: Collection<Call.State?>): Boolean =
            states.any { it != null && it !in RINGING && it !in OVER }

        /**
         * Why a call ended, as Telecom names it. [status] is the SIP stack's call-log
         * status, which is where a CANCEL's Reason header lands: a 200 in it is a call
         * answered on another of the user's devices (the browser), a 6xx one declined
         * there, and anything else on a call that never stopped ringing a missed call.
         */
        fun disconnectCause(state: Call.State?, status: Call.Status?, reason: Reason?): Int = when {
            status == Call.Status.AcceptedElsewhere -> DisconnectCause.ANSWERED_ELSEWHERE
            status == Call.Status.DeclinedElsewhere -> DisconnectCause.REJECTED
            status == Call.Status.Missed -> DisconnectCause.MISSED
            reason == Reason.Busy -> DisconnectCause.BUSY
            reason == Reason.Declined -> DisconnectCause.REMOTE
            state == Call.State.Error -> DisconnectCause.ERROR
            else -> DisconnectCause.REMOTE
        }
    }
}

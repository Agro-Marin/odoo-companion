package com.odoocompanion.softphone

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import android.telecom.DisconnectCause
import android.util.Log
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.Reason
import org.linphone.core.RegistrationState

@SuppressLint("StaticFieldLeak")
object SoftphoneCalls {
    private const val TAG = "SoftphoneCalls"
    private const val MAX_CALL_MILLIS = 4L * 60 * 60 * 1000

    @Volatile
    var core: Core? = null

    lateinit var appContext: Context

    @Volatile
    var registration: RegistrationState? = null

    val book = SoftphoneCallBook<SoftphoneConnection>()

    var onChanged: (() -> Unit)? = null

    var speaker = false
        private set

    private var earpiece = false
    private var proximity: PowerManager.WakeLock? = null

    val call: Call?
        get() = book.current?.let(::callOf)

    val remote: String?
        get() = call?.let(::nameOf)

    fun nameOf(call: Call): String? = call.remoteAddress.let { it.displayName ?: it.username }

    fun callOf(connection: SoftphoneConnection): Call? =
        book.callOf(connection)?.let { id -> core?.getCallByCallid(id) }

    fun onCallState(context: Context, call: Call, state: Call.State?) {
        Log.i(TAG, "Call $state")
        val id = call.callLog.callId
        if (id == null) {
            if (state == Call.State.IncomingReceived) call.decline(Reason.Busy)
            return
        }
        when (state) {
            Call.State.IncomingReceived -> offer(context, call, id)

            Call.State.Connected, Call.State.StreamsRunning -> book.connectionOf(id)?.activate()

            Call.State.End, Call.State.Error, Call.State.Released -> {
                val ended = book.ended(id)
                if (ended != null) {
                    val cause = SoftphoneCallBook.disconnectCause(
                        state,
                        call.callLog.status,
                        call.reason,
                    )
                    ended.connection?.end(cause)
                    SoftphoneRinging.stop(context)
                }
            }

            else -> Unit
        }
        changed()
    }

    private fun offer(context: Context, call: Call, id: String) {
        when (book.offer(id)) {
            Offer.DECLINE_BUSY -> call.decline(Reason.Busy)

            Offer.REPORT -> SoftphoneTelecom.reportIncoming(
                context,
                id,
                call.remoteAddress.asStringUriOnly(),
                nameOf(call),
            )
        }
    }

    fun refuse(callId: String?) {
        val refused = book.refuseIncoming(callId) ?: return
        core?.getCallByCallid(refused)?.decline(Reason.Busy)
        changed()
    }

    fun answer(connection: SoftphoneConnection) {
        callOf(connection)?.accept()
    }

    fun hangUp(connection: SoftphoneConnection) {
        val call = callOf(connection) ?: return
        if (call.state in INCOMING) call.decline(Reason.Declined) else call.terminate()
    }

    val ringing: Boolean
        get() = call?.state in INCOMING

    fun hangUpCurrent(context: Context) {
        val connection = book.current ?: return SoftphoneRinging.stop(context)
        if (ringing) connection.reject() else connection.disconnect()
    }

    val held: Boolean
        get() = call?.state == Call.State.Paused

    private val INCOMING = setOf(Call.State.IncomingReceived, Call.State.IncomingEarlyMedia)

    fun hold(connection: SoftphoneConnection): Boolean = callOf(connection)?.pause() == 0

    fun resume(connection: SoftphoneConnection): Boolean = callOf(connection)?.resume() == 0

    fun dial(number: String, connection: SoftphoneConnection): Boolean {
        val core = core ?: return false
        val address = core.interpretUrl(number, true) ?: return false
        val call = core.inviteAddress(address) ?: return false
        val id = call.callLog.callId
        if (id == null) {
            call.terminate()
            return false
        }
        book.attachOutgoing(id, connection)
        changed()
        return true
    }

    fun setMuted(muted: Boolean) {
        core?.isMicEnabled = !muted
        InCallActivity.refresh()
    }

    val muted: Boolean
        get() = core?.isMicEnabled == false

    fun onRoute(speaker: Boolean, earpiece: Boolean) {
        this.speaker = speaker
        this.earpiece = earpiece
        changed()
    }

    fun abandon() {
        book.clear().forEach { it.end(DisconnectCause.LOCAL) }
        changed()
    }

    fun onTheLine(): Boolean = SoftphoneCallBook.onTheLine(core?.calls?.map { it.state }.orEmpty())

    fun changed() {
        holdProximity(onTheLine() && earpiece)
        onChanged?.invoke()
        InCallActivity.refresh()
    }

    private fun holdProximity(wanted: Boolean) {
        val lock = proximity ?: newProximityLock()?.also { proximity = it } ?: return
        if (wanted && !lock.isHeld) {
            lock.acquire(MAX_CALL_MILLIS)
        } else if (!wanted && lock.isHeld) {
            lock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
        }
    }

    private fun newProximityLock(): PowerManager.WakeLock? {
        if (!::appContext.isInitialized) return null
        val power = appContext.getSystemService(PowerManager::class.java) ?: return null
        if (!power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            return null
        }
        return power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "companion:call")
            .apply { setReferenceCounted(false) }
    }
}

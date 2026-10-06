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

/**
 * The softphone's calls, between the SIP stack that carries them and the
 * system's Telecom framework that rings, routes audio and shows them. Which
 * call owns which connection is [SoftphoneCallBook]'s, checkable on its own;
 * this is the glue that applies it. Everything here runs on the main thread:
 * the SIP stack iterates there and Telecom calls back there.
 */
@SuppressLint("StaticFieldLeak") // the application context, never an activity
object SoftphoneCalls {
    private const val TAG = "SoftphoneCalls"
    private const val MAX_CALL_MILLIS = 4L * 60 * 60 * 1000

    @Volatile
    var core: Core? = null

    lateinit var appContext: Context

    @Volatile
    var registration: RegistrationState? = null

    val book = SoftphoneCallBook<SoftphoneConnection>()

    /** Told after every call event: the service moves its foreground type with it. */
    var onChanged: (() -> Unit)? = null

    /** Whether audio goes to the loudspeaker, as Telecom last reported the route. */
    var speaker = false
        private set

    private var earpiece = false
    private var proximity: PowerManager.WakeLock? = null

    /** The call the screen shows: the one whose connection Telecom holds. */
    val call: Call?
        get() = book.current?.let(::callOf)

    /** Whoever is on the other end of the current call, for the screen. */
    val remote: String?
        get() = call?.let(::nameOf)

    fun nameOf(call: Call): String? = call.remoteAddress.let { it.displayName ?: it.username }

    fun callOf(connection: SoftphoneConnection): Call? =
        book.callOf(connection)?.let { id -> core?.getCallByCallid(id) }

    fun onCallState(context: Context, call: Call, state: Call.State?) {
        Log.i(TAG, "Call $state")
        val id = call.callLog.callId
        if (id == null) {
            // nothing to key it by, so nothing it could be matched to
            if (state == Call.State.IncomingReceived) call.decline(Reason.Busy)
            return
        }
        when (state) {
            Call.State.IncomingReceived -> offer(context, call, id)

            Call.State.Connected, Call.State.StreamsRunning -> book.connectionOf(id)?.setActive()

            Call.State.End, Call.State.Error, Call.State.Released -> {
                // only the call a connection belongs to may end it: a second
                // call declined busy ends here too, and must leave the first alone
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

            Offer.REPORT -> {
                val address = call.remoteAddress.asStringUriOnly()
                if (!SoftphoneTelecom.reportIncoming(context, id, address)) refuse(id)
            }
        }
    }

    /** Telecom will not take the reported call: decline it so the caller hears busy. */
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
        if (call.state == Call.State.IncomingReceived ||
            call.state == Call.State.IncomingEarlyMedia
        ) {
            call.decline(Reason.Declined)
        } else {
            call.terminate()
        }
    }

    fun hold(connection: SoftphoneConnection): Boolean = callOf(connection)?.pause() == 0

    fun resume(connection: SoftphoneConnection): Boolean = callOf(connection)?.resume() == 0

    /** Dials out; the new call belongs to [connection]. False when it could not be placed. */
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

    /** Telecom's report of where the audio goes. */
    fun onRoute(speaker: Boolean, earpiece: Boolean) {
        this.speaker = speaker
        this.earpiece = earpiece
        changed()
    }

    /** Every connection still open, for a SIP stack going away under them. */
    fun abandon() {
        book.clear().forEach { it.end(DisconnectCause.ERROR) }
        changed()
    }

    fun onTheLine(): Boolean = SoftphoneCallBook.onTheLine(core?.calls?.map { it.state }.orEmpty())

    fun changed() {
        holdProximity(onTheLine() && earpiece)
        onChanged?.invoke()
        InCallActivity.refresh()
    }

    // the screen goes dark against the ear, as the dialer's does, so a cheek
    // cannot hang up or mute the call
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

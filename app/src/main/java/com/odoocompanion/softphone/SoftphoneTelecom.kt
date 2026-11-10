package com.odoocompanion.softphone

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.net.toUri
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallException
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The softphone as a calling app the system knows about, through Core-Telecom:
 * the system routes its audio (earpiece, speaker, Bluetooth, car) and holds it
 * against a cellular call the way it holds one cellular call against another.
 * Core-Telecom drives the platform's VoIP call API from Android 14, and a
 * self-managed connection service of its own below it.
 */
object SoftphoneTelecom {
    private const val TAG = "SoftphoneTelecom"

    // Core-Telecom calls back on the context a call was added from, so every
    // call runs on the main thread, where the SIP stack iterates
    private val scope = MainScope()

    @SuppressLint("StaticFieldLeak") // built on the application context, never an activity
    private var calls: CallsManager? = null

    // The account builds before Core-Telecom registered on their own connection
    // service. Telecom keeps an account across an upgrade that removed its service,
    // and registerAppWithTelecom replaces only Core-Telecom's own.
    private const val RETIRED_SERVICE = "com.odoocompanion.softphone.SoftphoneConnectionService"
    private const val RETIRED_ACCOUNT_ID = "company_line"

    fun registerAccount(context: Context) {
        context.getSystemService(TelecomManager::class.java).unregisterPhoneAccount(
            PhoneAccountHandle(ComponentName(context, RETIRED_SERVICE), RETIRED_ACCOUNT_ID),
        )
        calls = CallsManager(context.applicationContext).apply {
            registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
        }
    }

    /** Hands an INVITE to the system to ring; a refusal declines it busy. */
    fun reportIncoming(context: Context, callId: String, remote: String, name: String?) {
        add(
            context,
            attributes(remote.toUri(), name, CallAttributesCompat.DIRECTION_INCOMING),
            onRefused = { SoftphoneCalls.refuse(callId) },
        ) { connection ->
            // the caller may have hung up between the INVITE and this
            if (SoftphoneCalls.book.attachIncoming(callId, connection)) {
                SoftphoneCalls.changed()
                connection.ring()
            } else {
                connection.end(DisconnectCause.MISSED)
            }
        }
    }

    fun placeCall(context: Context, number: String) {
        add(
            context,
            attributes(
                Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null),
                number,
                CallAttributesCompat.DIRECTION_OUTGOING,
            ),
            onRefused = {},
        ) { connection ->
            if (SoftphoneCalls.dial(number, connection)) {
                InCallActivity.show(context)
            } else {
                connection.end(DisconnectCause.LOCAL)
            }
        }
    }

    internal fun attributes(address: Uri, name: String?, direction: Int) = CallAttributesCompat(
        displayName = name ?: address.schemeSpecificPart,
        address = address,
        direction = direction,
        callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
        // hold is what lets the system keep this call when a cellular one is
        // answered over it; without it the system disconnects the company call
        callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE,
        // the phone system records every company call and Odoo already holds it:
        // in the handset's call log, CallLogSyncWorker would upload it again as a
        // second phone.call. The platform logs an app's call only when the app
        // opts in (an account extra, or from 36.1 an ACTION_CALL_BACK activity);
        // this one does not, and from 36.1 this says so per call.
        isLogExcluded = true,
    )

    private fun add(
        context: Context,
        attributes: CallAttributesCompat,
        onRefused: () -> Unit,
        onAdded: (SoftphoneConnection) -> Unit,
    ) {
        // no account yet: the softphone is not running, so there is no line to call on
        val calls = calls ?: return onRefused()
        scope.launch {
            var connection: SoftphoneConnection? = null
            // Telecom asking before the block ran has nothing to act on, and a
            // callback that returns has told Telecom it was done: refuse instead
            val added = { checkNotNull(connection) { "The call is not set up yet" } }
            try {
                calls.addCall(
                    attributes,
                    onAnswer = { added().onSystemAnswer() },
                    onDisconnect = { added().onSystemDisconnect() },
                    onSetActive = { added().onSystemResume() },
                    onSetInactive = { added().onSystemHold() },
                ) {
                    connection = SoftphoneConnection(context.applicationContext, this).also {
                        it.follow()
                        onAdded(it)
                    }
                }
            } catch (e: CallException) {
                // the system cannot take a call now (an emergency call, or one it
                // cannot hold), or ended it
                Log.w(TAG, "The system did not keep the call (${e.code})", e)
            } catch (e: IllegalStateException) {
                // a callback the SIP stack could not carry out: Core-Telecom tears
                // the call down without telling onDisconnect
                Log.w(TAG, "The system ended a call the SIP stack would not follow", e)
            } finally {
                // never added: an INVITE still needs an answer, or it rings on
                // with nothing on the handset to show for it
                connection?.onSessionEnded() ?: onRefused()
            }
        }
    }
}

/**
 * The system's side of one company call, and what the system asks of it. The
 * SIP call it belongs to is [SoftphoneCallBook]'s to know.
 */
class SoftphoneConnection internal constructor(
    private val context: Context,
    private val control: CallControlScope,
    // the SIP stack ends its call inside this, and reports that end before it returns
    private val hangUpSip: (SoftphoneConnection) -> Unit = SoftphoneCalls::hangUp,
) {
    // Owned here rather than launched on [control]: Core-Telecom waits for that
    // scope's children before it returns, and the endpoint and mute flows never
    // finish on their own, so the session would never be seen to end.
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var endpoints: List<CallEndpointCompat> = emptyList()
    private var systemMuted: Boolean? = null
    private var ended = false
    private var sipReleased = false

    internal fun follow() {
        jobs.launch { control.isMuted.collect(::onSystemMute) }
        jobs.launch { control.availableEndpoints.collect { endpoints = it } }
        jobs.launch {
            control.currentCallEndpoint.collect {
                SoftphoneCalls.onRoute(
                    speaker = it.type == CallEndpointCompat.TYPE_SPEAKER,
                    earpiece = it.type == CallEndpointCompat.TYPE_EARPIECE,
                )
            }
        }
    }

    // The app rings the call itself: the system shows no ringing for it, and
    // the CallStyle notice is what Core-Telecom wants up within five seconds.
    internal fun ring() {
        SoftphoneRinging.ring(context, SoftphoneCalls.callOf(this)?.let(SoftphoneCalls::nameOf))
    }

    /** The answer button. */
    fun answer() {
        SoftphoneRinging.stop(context)
        jobs.launch {
            when (val result = control.answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL)) {
                is CallControlResult.Success -> SoftphoneCalls.answer(this@SoftphoneConnection)

                is CallControlResult.Error -> Log.w(
                    TAG,
                    "The system would not answer (${result.errorCode})"
                )
            }
        }
    }

    /** Declines a call still ringing. */
    fun reject() = hangUp(DisconnectCause.REJECTED)

    /** Hangs up a call on the line. */
    fun disconnect() = hangUp(DisconnectCause.LOCAL)

    // The system's call ends first, with this side's cause: the SIP stack reports
    // the end of its call before hangUpSip returns, and that report cannot tell
    // who hung up, so going second it ended a declined or hung-up call as REMOTE.
    private fun hangUp(cause: Int) {
        val release = !sipReleased
        end(cause)
        if (release) hangUpSip(this)
    }

    /**
     * The resume button. Nothing else takes back a call the system held for a
     * cellular one: the system resumes none of an app's calls on its own.
     */
    fun resume() {
        jobs.launch {
            when (val result = control.setActive()) {
                is CallControlResult.Success ->
                    if (!SoftphoneCalls.resume(this@SoftphoneConnection)) {
                        // the system counts the call active again: hand the focus back
                        Log.w(TAG, "The SIP stack would not resume the call")
                        control.setInactive()
                    }

                is CallControlResult.Error ->
                    Log.w(TAG, "The system would not resume the call (${result.errorCode})")
            }
        }
    }

    /** The SIP call is up: the system makes it the active call. */
    fun activate() {
        jobs.launch {
            val result = control.setActive()
            if (result is CallControlResult.Error) {
                Log.w(TAG, "The system would not make the call active (${result.errorCode})")
            }
        }
    }

    /** Asks the system for the loudspeaker, or back to the earpiece or headset. */
    fun routeToSpeaker(speaker: Boolean) {
        val endpoint = endpointFor(speaker, endpoints) ?: return
        jobs.launch {
            val result = control.requestEndpointChange(endpoint)
            if (result is CallControlResult.Error) {
                Log.w(TAG, "The system kept the audio route (${result.errorCode})")
            }
        }
    }

    fun end(cause: Int) {
        if (ended) return
        ended = true
        // the SIP call is over or never was; nothing is left to hang up there
        sipReleased = true
        SoftphoneRinging.stop(context)
        jobs.launch { control.disconnect(DisconnectCause(cause)) }
    }

    // A headset, a car or a watch answered it.
    internal fun onSystemAnswer() {
        SoftphoneRinging.stop(context)
        SoftphoneCalls.answer(this)
    }

    internal fun onSystemDisconnect() {
        ended = true
        SoftphoneRinging.stop(context)
        releaseSip()
    }

    // Throwing is how a callback refuses; Core-Telecom then ends the call.
    internal fun onSystemHold() {
        check(SoftphoneCalls.hold(this)) { "The SIP stack would not hold the call" }
    }

    internal fun onSystemResume() {
        check(SoftphoneCalls.resume(this)) { "The SIP stack would not resume the call" }
    }

    // However the system's call ended -- including a refused callback, which
    // Core-Telecom does not report to onDisconnect -- the SIP call must not
    // outlive it, still holding the line and the microphone.
    internal fun onSessionEnded() {
        ended = true
        SoftphoneRinging.stop(context)
        releaseSip()
        jobs.cancel()
    }

    private fun releaseSip() {
        if (sipReleased) return
        sipReleased = true
        hangUpSip(this)
    }

    // A headset's or a car's mute button reaches the call through Telecom. Only
    // a change is applied: the flow repeats the system's flag, which knows
    // nothing of the screen's own mute button and would undo it.
    private fun onSystemMute(muted: Boolean) {
        if (muted == systemMuted) return
        systemMuted = muted
        SoftphoneCalls.setMuted(muted)
    }

    internal companion object {
        private const val TAG = "SoftphoneConnection"

        private val SPEAKER = listOf(CallEndpointCompat.TYPE_SPEAKER)
        private val PRIVATE = listOf(
            CallEndpointCompat.TYPE_WIRED_HEADSET,
            CallEndpointCompat.TYPE_BLUETOOTH,
            CallEndpointCompat.TYPE_EARPIECE,
        )

        /** The loudspeaker, or the most private route there is, by the system's own list. */
        fun endpointFor(
            speaker: Boolean,
            available: List<CallEndpointCompat>
        ): CallEndpointCompat? = (if (speaker) SPEAKER else PRIVATE).firstNotNullOfOrNull { type ->
            available.firstOrNull { it.type == type }
        }
    }
}

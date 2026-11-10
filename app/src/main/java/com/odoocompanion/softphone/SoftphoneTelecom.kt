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

object SoftphoneTelecom {
    private const val TAG = "SoftphoneTelecom"

    private val scope = MainScope()

    @SuppressLint("StaticFieldLeak")
    private var calls: CallsManager? = null

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

    fun reportIncoming(context: Context, callId: String, remote: String, name: String?) {
        add(
            context,
            attributes(remote.toUri(), name, CallAttributesCompat.DIRECTION_INCOMING),
            onRefused = { SoftphoneCalls.refuse(callId) },
        ) { connection ->
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
        callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE,
        isLogExcluded = true,
    )

    private fun add(
        context: Context,
        attributes: CallAttributesCompat,
        onRefused: () -> Unit,
        onAdded: (SoftphoneConnection) -> Unit,
    ) {
        val calls = calls ?: return onRefused()
        scope.launch {
            var connection: SoftphoneConnection? = null
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
                Log.w(TAG, "The system did not keep the call (${e.code})", e)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "The system ended a call the SIP stack would not follow", e)
            } finally {
                connection?.onSessionEnded() ?: onRefused()
            }
        }
    }
}

class SoftphoneConnection internal constructor(
    private val context: Context,
    private val control: CallControlScope,
    private val hangUpSip: (SoftphoneConnection) -> Unit = SoftphoneCalls::hangUp,
) {
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

    internal fun ring() {
        SoftphoneRinging.ring(context, SoftphoneCalls.callOf(this)?.let(SoftphoneCalls::nameOf))
    }

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

    fun reject() = hangUp(DisconnectCause.REJECTED)

    fun disconnect() = hangUp(DisconnectCause.LOCAL)

    private fun hangUp(cause: Int) {
        val release = !sipReleased
        end(cause)
        if (release) hangUpSip(this)
    }

    fun resume() {
        jobs.launch {
            when (val result = control.setActive()) {
                is CallControlResult.Success ->
                    if (!SoftphoneCalls.resume(this@SoftphoneConnection)) {
                        Log.w(TAG, "The SIP stack would not resume the call")
                        control.setInactive()
                    }

                is CallControlResult.Error ->
                    Log.w(TAG, "The system would not resume the call (${result.errorCode})")
            }
        }
    }

    fun activate() {
        jobs.launch {
            val result = control.setActive()
            if (result is CallControlResult.Error) {
                Log.w(TAG, "The system would not make the call active (${result.errorCode})")
            }
        }
    }

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
        sipReleased = true
        SoftphoneRinging.stop(context)
        jobs.launch { control.disconnect(DisconnectCause(cause)) }
    }

    internal fun onSystemAnswer() {
        SoftphoneRinging.stop(context)
        SoftphoneCalls.answer(this)
    }

    internal fun onSystemDisconnect() {
        ended = true
        SoftphoneRinging.stop(context)
        releaseSip()
    }

    internal fun onSystemHold() {
        check(SoftphoneCalls.hold(this)) { "The SIP stack would not hold the call" }
    }

    internal fun onSystemResume() {
        check(SoftphoneCalls.resume(this)) { "The SIP stack would not resume the call" }
    }

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

        fun endpointFor(
            speaker: Boolean,
            available: List<CallEndpointCompat>
        ): CallEndpointCompat? = (if (speaker) SPEAKER else PRIVATE).firstNotNullOfOrNull { type ->
            available.firstOrNull { it.type == type }
        }
    }
}

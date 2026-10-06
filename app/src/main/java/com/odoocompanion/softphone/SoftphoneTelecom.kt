package com.odoocompanion.softphone

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.OutcomeReceiver
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.core.os.BundleCompat
import com.odoocompanion.R

/**
 * The softphone as a self-managed calling app: the system rings it, routes its
 * audio (earpiece, speaker, Bluetooth, car) and holds it against a cellular call
 * the way it holds one cellular call against another.
 */
object SoftphoneTelecom {
    private const val TAG = "SoftphoneTelecom"
    private const val ACCOUNT_ID = "company_line"
    const val EXTRA_CALL_ID = "com.odoocompanion.softphone.CALL_ID"

    fun handle(context: Context) = PhoneAccountHandle(
        ComponentName(context, SoftphoneConnectionService::class.java),
        ACCOUNT_ID,
    )

    fun registerAccount(context: Context) {
        val account = PhoneAccount.builder(
            handle(context),
            context.getString(R.string.softphone_account_label)
        )
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_SIP, PhoneAccount.SCHEME_TEL))
            .build()
        context.getSystemService(TelecomManager::class.java).registerPhoneAccount(account)
    }

    /** Hands an INVITE to Telecom to ring; false when Telecom will not take it. */
    fun reportIncoming(context: Context, callId: String, remote: String): Boolean {
        val telecom = context.getSystemService(TelecomManager::class.java)
        val handle = handle(context)
        // the Call-ID travels both ways Telecom may hand extras back
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, remote.toUri())
            putString(EXTRA_CALL_ID, callId)
            putBundle(
                TelecomManager.EXTRA_INCOMING_CALL_EXTRAS,
                Bundle().apply { putString(EXTRA_CALL_ID, callId) },
            )
        }
        return try {
            if (!telecom.isIncomingCallPermitted(handle)) {
                Log.i(TAG, "The system cannot take another call now; declining busy")
                return false
            }
            telecom.addNewIncomingCall(handle, extras)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "The system refused the incoming call", e)
            false
        }
    }

    fun callIdOf(request: ConnectionRequest?): String? {
        val extras = request?.extras ?: return null
        return extras.getString(EXTRA_CALL_ID)
            ?: extras.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS)
                ?.getString(EXTRA_CALL_ID)
    }

    fun placeCall(context: Context, number: String) {
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle(context))
        }
        try {
            context.getSystemService(TelecomManager::class.java)
                .placeCall(Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null), extras)
        } catch (e: SecurityException) {
            Log.w(TAG, "The system refused the outgoing call", e)
        }
    }
}

class SoftphoneConnection(private val context: Context) : Connection() {
    private var endpoints: List<CallEndpoint> = emptyList()
    private var systemMuted: Boolean? = null

    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        // hold is what lets Telecom keep this call when a cellular one is
        // answered over it; without it the system disconnects the company call
        connectionCapabilities = CAPABILITY_HOLD or CAPABILITY_SUPPORT_HOLD or CAPABILITY_MUTE
        audioModeIsVoip = true
    }

    override fun onShowIncomingCallUi() {
        SoftphoneRinging.ring(context, SoftphoneCalls.callOf(this)?.let(SoftphoneCalls::nameOf))
    }

    override fun onSilence() {
        SoftphoneRinging.ring(
            context,
            SoftphoneCalls.callOf(this)?.let(SoftphoneCalls::nameOf),
            silent = true,
        )
    }

    override fun onAnswer() {
        SoftphoneRinging.stop(context)
        SoftphoneCalls.answer(this)
        setActive()
    }

    override fun onReject() {
        SoftphoneCalls.hangUp(this)
        end(DisconnectCause.REJECTED)
    }

    override fun onDisconnect() {
        SoftphoneCalls.hangUp(this)
        end(DisconnectCause.LOCAL)
    }

    override fun onAbort() = onDisconnect()

    override fun onHold() {
        if (SoftphoneCalls.hold(this)) setOnHold()
    }

    override fun onUnhold() {
        if (SoftphoneCalls.resume(this)) setActive()
    }

    @Deprecated("Superseded by the call endpoint callbacks from API 34")
    override fun onCallAudioStateChanged(state: CallAudioState?) {
        state ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            onSystemMute(state.isMuted)
            SoftphoneCalls.onRoute(
                speaker = state.route == CallAudioState.ROUTE_SPEAKER,
                earpiece = state.route == CallAudioState.ROUTE_EARPIECE,
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onCallEndpointChanged(endpoint: CallEndpoint) {
        SoftphoneCalls.onRoute(
            speaker = endpoint.endpointType == CallEndpoint.TYPE_SPEAKER,
            earpiece = endpoint.endpointType == CallEndpoint.TYPE_EARPIECE,
        )
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onAvailableCallEndpointsChanged(available: List<CallEndpoint>) {
        endpoints = available
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onMuteStateChanged(isMuted: Boolean) {
        onSystemMute(isMuted)
    }

    // A headset's or a car's mute button reaches the call through Telecom. Only
    // a change is applied: every route change repeats the system's flag, which
    // knows nothing of the screen's own mute button and would undo it.
    private fun onSystemMute(muted: Boolean) {
        if (muted == systemMuted) return
        systemMuted = muted
        SoftphoneCalls.setMuted(muted)
    }

    /** Asks Telecom for the loudspeaker, or back to the earpiece or headset. */
    fun routeToSpeaker(speaker: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            requestEndpoint(speaker)
        } else {
            val route = if (speaker) {
                CallAudioState.ROUTE_SPEAKER
            } else {
                CallAudioState.ROUTE_WIRED_OR_EARPIECE
            }
            @Suppress("DEPRECATION")
            setAudioRoute(route)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun requestEndpoint(speaker: Boolean) {
        val wanted = if (speaker) {
            listOf(CallEndpoint.TYPE_SPEAKER)
        } else {
            listOf(
                CallEndpoint.TYPE_WIRED_HEADSET,
                CallEndpoint.TYPE_BLUETOOTH,
                CallEndpoint.TYPE_EARPIECE,
            )
        }
        val endpoint = wanted.firstNotNullOfOrNull { type ->
            endpoints.firstOrNull { it.endpointType == type }
        } ?: return
        requestCallEndpointChange(
            endpoint,
            context.mainExecutor,
            object : OutcomeReceiver<Void, CallEndpointException> {
                override fun onResult(result: Void?) = Unit

                override fun onError(error: CallEndpointException) {
                    Log.w(TAG, "The system kept the audio route", error)
                }
            },
        )
    }

    fun end(cause: Int) {
        if (state == STATE_DISCONNECTED) return
        SoftphoneRinging.stop(context)
        setDisconnected(DisconnectCause(cause))
        destroy()
    }

    private companion object {
        const val TAG = "SoftphoneConnection"
    }
}

class SoftphoneConnectionService : ConnectionService() {
    override fun onCreateIncomingConnection(
        account: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection {
        val connection = SoftphoneConnection(applicationContext).apply {
            setAddress(
                request?.extras?.let {
                    BundleCompat.getParcelable(
                        it,
                        TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                        Uri::class.java
                    )
                },
                TelecomManager.PRESENTATION_ALLOWED,
            )
            setRinging()
        }
        // the caller may have hung up between the INVITE and this
        if (!SoftphoneCalls.book.attachIncoming(SoftphoneTelecom.callIdOf(request), connection)) {
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.MISSED))
        }
        SoftphoneCalls.changed()
        return connection
    }

    // Telecom would not ring it (an emergency call, or a call it cannot hold):
    // the INVITE still needs an answer, or the caller hears ringing until it times out
    override fun onCreateIncomingConnectionFailed(
        account: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) {
        Log.i(TAG, "The system did not take the incoming call; declining busy")
        SoftphoneCalls.refuse(SoftphoneTelecom.callIdOf(request))
    }

    override fun onCreateOutgoingConnection(
        account: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection {
        val number = request?.address?.schemeSpecificPart.orEmpty()
        val connection = SoftphoneConnection(applicationContext).apply {
            setAddress(request?.address, TelecomManager.PRESENTATION_ALLOWED)
            setDialing()
        }
        if (!SoftphoneCalls.dial(number, connection)) {
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
        }
        InCallActivity.show(this)
        return connection
    }

    private companion object {
        const val TAG = "SoftphoneTelecom"
    }
}

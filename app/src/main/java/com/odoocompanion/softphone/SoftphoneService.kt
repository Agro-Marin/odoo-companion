package com.odoocompanion.softphone

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.odoocompanion.CompanionApp
import com.odoocompanion.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.linphone.core.AVPFMode
import org.linphone.core.Account
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.MediaEncryption
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType

/**
 * Keeps this handset registered as its user's extension on the company phone
 * system, so a call to the company number rings here as it rings in the
 * browser. The phone system records every call; nothing is recorded here.
 *
 * Waiting for a call it runs in the foreground as `specialUse`, a type Android
 * 15 still lets a boot start; only while a call is on the line is it also
 * `phoneCall` and `microphone`, the two types a boot start may not take.
 */
class SoftphoneService : LifecycleService() {
    private var core: Core? = null
    private var running: SoftphoneSettings? = null

    // what arrived while a call was up, applied when the line is free again
    private var waiting: SoftphoneSettings? = null
    private var stopWhenFree = false

    private var foregroundTypes: Int? = null

    private val callsChanged: () -> Unit = { onCallsChanged() }

    private val listener = object : CoreListenerStub() {
        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: RegistrationState?,
            message: String,
        ) {
            Log.i(TAG, "Registration $state: $message")
            SoftphoneCalls.registration = state
            notifyStatus()
        }

        override fun onCallStateChanged(
            core: Core,
            call: Call,
            state: Call.State?,
            message: String,
        ) {
            SoftphoneCalls.onCallState(applicationContext, call, state)
        }
    }

    override fun onCreate() {
        super.onCreate()
        SoftphoneCalls.appContext = applicationContext
        SoftphoneRinging.ensureChannel(this)
        if (!enterForeground(onTheLine = false)) {
            SoftphoneRetry.schedule(this)
            stopSelf()
            return
        }
        alive = true
        SoftphoneCalls.onChanged = callsChanged
        SoftphoneTelecom.registerAccount(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (foregroundTypes == null) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_DECLINE -> SoftphoneCalls.book.current?.onReject()

            ACTION_STOP -> stopWhenFree()

            else -> lifecycleScope.launch {
                // the app holds the one store: DataStore refuses two on one file
                val settings = CompanionApp.from(applicationContext).softphones.current()
                if (settings == null) {
                    stopWhenFree()
                } else {
                    stopWhenFree = false
                    configure(settings)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (SoftphoneCalls.onChanged === callsChanged) SoftphoneCalls.onChanged = null
        if (foregroundTypes != null) alive = false
        release()
        running = null
        super.onDestroy()
    }

    private fun stopWhenFree() {
        if (lineFree()) {
            stopSelf()
        } else {
            stopWhenFree = true
        }
    }

    // a change of extension or secret never cuts a call short
    private fun configure(settings: SoftphoneSettings) {
        if (settings == running) {
            waiting = null
        } else if (lineFree()) {
            register(settings)
        } else {
            Log.i(TAG, "Softphone settings changed during a call; applying them after it")
            waiting = settings
        }
    }

    private fun lineFree(): Boolean = SoftphoneCalls.book.idle && (core?.callsNb ?: 0) == 0

    private fun onCallsChanged() {
        enterForeground(SoftphoneCalls.onTheLine())
        if (waiting == null && !stopWhenFree) return
        // the SIP stack is mid-callback here: replace or stop it after it
        // returns, which Main.immediate, the scope's own dispatcher, would not wait for
        lifecycleScope.launch(Dispatchers.Main) {
            if (!lineFree()) return@launch
            if (stopWhenFree) {
                stopSelf()
                return@launch
            }
            waiting?.let {
                waiting = null
                register(it)
            }
        }
    }

    /** Foreground as the moment needs, false when the system refuses even that. */
    private fun enterForeground(onTheLine: Boolean): Boolean {
        val choices = foregroundChoices(onTheLine)
        if (foregroundTypes == choices.first()) return true
        for (types in choices) {
            if (types == foregroundTypes) return true
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), types)
                foregroundTypes = types
                return true
            } catch (e: SecurityException) {
                // the microphone, from the background: the call goes on as
                // phoneCall, and the call screen coming up takes it again
                Log.w(TAG, "Foreground type 0x${types.toString(HEX)} refused", e)
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException: never a crash, which
                // would take the location service down with it
                Log.w(TAG, "Foreground type 0x${types.toString(HEX)} not allowed now", e)
            }
        }
        return false
    }

    private fun release() {
        val old = core ?: return
        SoftphoneCalls.core = null
        SoftphoneCalls.abandon()
        old.removeListener(listener)
        old.stop()
        core = null
    }

    private fun register(settings: SoftphoneSettings) {
        release()
        val factory = Factory.instance()
        // read when the core is created, so they go in before it is
        val config = factory.createConfigFromString(SoftphoneEngine.CONFIG)
        val core = factory.createCoreWithConfig(config, applicationContext).apply {
            isVideoCaptureEnabled = false
            isVideoDisplayEnabled = false
            // DTLS-SRTP and ICE, as the phone system's WebRTC extension expects;
            // a call is never sent in the clear
            mediaEncryption = MediaEncryption.DTLS
            isMediaEncryptionMandatory = true
            isKeepAliveEnabled = true
            // SoftphoneRinging rings, with the handset's ringtone
            isNativeRingingEnabled = false
            ring = null
            ExtraTrust.rootCaData(applicationContext, rootCa)?.let(::setRootCaData)
            addListener(listener)
        }
        core.addAuthInfo(
            factory.createAuthInfo(
                settings.username,
                null,
                settings.secret,
                null,
                null,
                settings.domain,
                null,
            ),
        )
        val params = core.createAccountParams().apply {
            identityAddress = factory.createAddress(settings.identity)
            serverAddress = factory.createAddress(settings.serverAddress)?.apply {
                transport = TransportType.Tls
            }
            isRegisterEnabled = true
            avpfMode = AVPFMode.Enabled
            natPolicy = core.createNatPolicy().apply { isIceEnabled = true }
        }
        val account = core.createAccount(params)
        core.addAccount(account)
        core.defaultAccount = account
        core.start()
        this.core = core
        SoftphoneCalls.core = core
        running = settings
        Log.i(TAG, "Registering $settings")
    }

    private fun notifyStatus() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_softphone),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val text = when (SoftphoneCalls.registration) {
            RegistrationState.Ok -> R.string.notification_softphone_ready
            RegistrationState.Failed -> R.string.notification_softphone_failed
            else -> R.string.notification_softphone_connecting
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, InCallActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_softphone_title))
            .setContentText(getString(text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val TAG = "SoftphoneService"
        private const val CHANNEL_ID = "softphone"
        private const val NOTIFICATION_ID = 2
        private const val HEX = 16
        private const val ACTION_DECLINE = "com.odoocompanion.softphone.DECLINE"
        private const val ACTION_STOP = "com.odoocompanion.softphone.STOP"

        // ServiceCompat drops a type the running platform does not define, so
        // below API 34 the service waits as a plain foreground service
        @SuppressLint("InlinedApi")
        private const val IDLE = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        private const val CALL_TYPES_NO_MICROPHONE =
            IDLE or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL

        @SuppressLint("InlinedApi")
        private const val CALL_TYPES =
            CALL_TYPES_NO_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

        /**
         * The foreground types to try, best first. specialUse stays in every set:
         * the reason the system recorded for a boot start is kept for the
         * service's life, and Android 15 refuses a later set holding none of the
         * types a boot start may take just as it refuses the boot start itself.
         * Without the microphone, which the background cannot take, a call still
         * goes on as phoneCall.
         */
        internal fun foregroundChoices(onTheLine: Boolean): List<Int> =
            if (onTheLine) listOf(CALL_TYPES, CALL_TYPES_NO_MICROPHONE) else listOf(IDLE)

        // whether stop has anything to stop: starting the service only to stop
        // it would put it in the foreground first
        @Volatile
        private var alive = false

        val isRunning: Boolean get() = alive

        fun declineIntent(context: Context): Intent =
            Intent(context, SoftphoneService::class.java).setAction(ACTION_DECLINE)

        /** Starts or re-reads the settings; false when the system refused the start. */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SoftphoneService::class.java),
            )
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException, from a background trigger
            Log.w(TAG, "Softphone start not allowed now; will retry", e)
            false
        }

        /** Stops, once the line is free: a call in progress is finished, not cut. */
        fun stop(context: Context) {
            if (!alive) return
            val intent = Intent(context, SoftphoneService::class.java)
            try {
                context.startService(Intent(intent).setAction(ACTION_STOP))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Cannot ask the softphone to stop after its call; stopping it now", e)
                context.stopService(intent)
            }
        }
    }
}

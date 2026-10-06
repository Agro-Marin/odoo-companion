package com.odoocompanion.softphone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.odoocompanion.R
import com.odoocompanion.databinding.ActivityInCallBinding
import org.linphone.core.Call
import java.lang.ref.WeakReference

/** The company line's call screen: answer, hang up, mute, speaker, or dial. */
class InCallActivity : AppCompatActivity() {
    private lateinit var binding: ActivityInCallBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        binding = ActivityInCallBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.answer.setOnClickListener { answer() }
        binding.hangUp.setOnClickListener {
            val connection = SoftphoneCalls.book.current
            if (connection == null) {
                SoftphoneRinging.stop(this)
            } else if (SoftphoneCalls.call?.state == Call.State.IncomingReceived) {
                connection.onReject()
            } else {
                connection.onDisconnect()
            }
        }
        binding.mute.setOnClickListener { SoftphoneCalls.setMuted(!SoftphoneCalls.muted) }
        binding.speaker.setOnClickListener {
            SoftphoneCalls.book.current?.routeToSpeaker(!SoftphoneCalls.speaker)
        }
        binding.dial.setOnClickListener {
            val number = binding.number.text.toString().filter { it.isDigit() || it == '+' }
            if (number.isNotEmpty()) SoftphoneTelecom.placeCall(this, number)
        }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        current = WeakReference(this)
        // on screen, the service may take the microphone it could not take
        // from the background when the call was answered
        SoftphoneCalls.changed()
    }

    override fun onPause() {
        if (current?.get() === this) current = null
        super.onPause()
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_ANSWER) answer()
    }

    private fun answer() {
        SoftphoneRinging.stop(this)
        SoftphoneCalls.book.current?.onAnswer()
    }

    private fun render() {
        val call = SoftphoneCalls.call
        val state = call?.state
        val ringing = state == Call.State.IncomingReceived
        val inCall = call != null && !ringing
        binding.remote.text = SoftphoneCalls.remote.orEmpty()
        binding.status.text = getString(
            when {
                ringing -> R.string.softphone_ringing

                state == Call.State.Paused -> R.string.softphone_on_hold

                inCall -> R.string.softphone_in_call

                SoftphoneCalls.registration == org.linphone.core.RegistrationState.Ok ->
                    R.string.notification_softphone_ready

                else -> R.string.notification_softphone_connecting
            },
        )
        binding.answer.isVisible = ringing
        binding.hangUp.isVisible = call != null
        binding.mute.isVisible = inCall
        binding.speaker.isVisible = inCall
        binding.mute.text =
            getString(
                if (SoftphoneCalls.muted) R.string.softphone_unmute else R.string.softphone_mute
            )
        binding.speaker.text = getString(
            if (SoftphoneCalls.speaker) R.string.softphone_earpiece else R.string.softphone_speaker,
        )
        binding.dialer.isVisible = call == null
    }

    companion object {
        const val ACTION_ANSWER = "com.odoocompanion.softphone.ANSWER"

        @Volatile
        private var current: WeakReference<InCallActivity>? = null

        fun refresh() {
            current?.get()?.let { activity -> activity.runOnUiThread { activity.render() } }
        }

        fun intent(context: Context): Intent =
            Intent(context, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun show(context: Context) {
            context.startActivity(intent(context))
        }
    }
}

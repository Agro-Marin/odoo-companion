package com.odoocompanion.softphone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.odoocompanion.R
import com.odoocompanion.databinding.ActivityInCallBinding
import org.linphone.core.Call
import java.lang.ref.WeakReference

class InCallActivity : AppCompatActivity() {
    private lateinit var binding: ActivityInCallBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        binding = ActivityInCallBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.answer.setOnClickListener {
            if (SoftphoneCalls.held) SoftphoneCalls.book.current?.resume() else answer()
        }
        binding.hangUp.setOnClickListener { SoftphoneCalls.hangUpCurrent(this) }
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode !in SILENCING_KEYS || !SoftphoneCalls.ringing) {
            return super.onKeyDown(keyCode, event)
        }
        SoftphoneRinging.ring(this, SoftphoneCalls.remote, silent = true)
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        current = WeakReference(this)
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
        SoftphoneCalls.book.current?.answer()
    }

    private fun render() {
        val call = SoftphoneCalls.call
        val state = call?.state
        val ringing = SoftphoneCalls.ringing
        val held = SoftphoneCalls.held
        val inCall = call != null && !ringing
        binding.remote.text = SoftphoneCalls.remote.orEmpty()
        binding.status.text = getString(
            when {
                ringing -> R.string.softphone_ringing

                held -> R.string.softphone_on_hold

                inCall -> R.string.softphone_in_call

                SoftphoneCalls.registration == org.linphone.core.RegistrationState.Ok ->
                    R.string.notification_softphone_ready

                else -> R.string.notification_softphone_connecting
            },
        )
        binding.answer.isVisible = ringing || held
        binding.answer.text =
            getString(if (held) R.string.softphone_resume else R.string.softphone_answer)
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

        private val SILENCING_KEYS = setOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
        )

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

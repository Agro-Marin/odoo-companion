package com.odoocompanion.push

import android.util.Log
import com.odoocompanion.CompanionApp
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

class CompanionPushService : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val keys = endpoint.pubKeySet
        if (keys == null) {
            Log.w(TAG, "The distributor gave an endpoint without keys; Odoo cannot seal to it")
            return
        }
        CompanionApp.from(this).reportPushEndpoint(
            PushRegistration(endpoint.url, keys.pubKey, keys.auth),
        )
    }

    override fun onMessage(message: PushMessage, instance: String) {
        // only Odoo holds the auth secret, so only what it sealed is obeyed
        if (!message.decrypted) {
            Log.w(TAG, "Ignoring a wake-up that was not sealed to this handset")
            return
        }
        val command = PushCommand.parse(message.content)
        if (command == null) {
            Log.w(TAG, "Ignoring a wake-up this build does not know")
            return
        }
        CompanionApp.from(this).obey(command)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Log.w(TAG, "The distributor refused the registration: $reason")
    }

    override fun onUnregistered(instance: String) {
        CompanionApp.from(this).reportPushEndpoint(null)
    }

    private companion object {
        const val TAG = "CompanionPushService"
    }
}

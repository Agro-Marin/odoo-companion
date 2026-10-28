package com.odoocompanion.ui

import androidx.annotation.StringRes
import com.odoocompanion.R
import com.odoocompanion.whatsapp.Sighting

// What the WhatsApp card says, from what the handset has granted and what it
// has seen. Notification access comes first: without it nothing is seen, and
// asking for file access then would be asking for the second half of nothing.
data class WhatsAppCard(
    @StringRes val state: Int,
    // the grant the button opens, if one is missing
    val missing: WhatsAppGrant?,
    val seen: Int,
    val found: Int,
    val shown: List<Sighting>,
)

enum class WhatsAppGrant { NOTIFICATIONS, DISCONNECTED, ALL_FILES, CONTACTS }

object WhatsAppPanel {
    const val SHOWN = 5

    fun card(
        listenerOn: Boolean,
        listenerConnected: Boolean,
        storageOn: Boolean,
        contactsOn: Boolean,
        sightings: List<Sighting>,
    ): WhatsAppCard {
        val missing = when {
            !listenerOn -> WhatsAppGrant.NOTIFICATIONS
            !listenerConnected -> WhatsAppGrant.DISCONNECTED
            !storageOn -> WhatsAppGrant.ALL_FILES
            !contactsOn -> WhatsAppGrant.CONTACTS
            else -> null
        }
        val state = when (missing) {
            WhatsAppGrant.NOTIFICATIONS -> R.string.whatsapp_notifications_off

            WhatsAppGrant.DISCONNECTED -> R.string.whatsapp_disconnected

            WhatsAppGrant.ALL_FILES -> R.string.whatsapp_storage_off

            WhatsAppGrant.CONTACTS -> R.string.whatsapp_contacts_off

            null ->
                if (sightings.isEmpty()) R.string.whatsapp_none_yet else R.string.whatsapp_watching
        }
        return WhatsAppCard(
            state = state,
            missing = missing,
            seen = sightings.size,
            found = sightings.count { it.found },
            shown = sightings.take(SHOWN),
        )
    }
}

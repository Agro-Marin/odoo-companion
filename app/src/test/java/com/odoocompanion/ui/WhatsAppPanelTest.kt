package com.odoocompanion.ui

import com.odoocompanion.R
import com.odoocompanion.whatsapp.Sighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhatsAppPanelTest {
    private fun sighting(n: Int, found: Boolean = false) = Sighting(
        key = "k$n",
        app = "com.whatsapp",
        chat = "A",
        sender = "A",
        label = "D$n.pdf",
        at = n.toLong(),
        filePath = if (found) "/w/D$n.pdf" else null,
    )

    @Test
    fun `without notification access nothing is seen, so that is asked first`() {
        val card = WhatsAppPanel.card(
            listenerOn = false,
            listenerConnected = true,
            storageOn = false,
            contactsOn = true,
            sightings = emptyList(),
        )

        assertEquals(WhatsAppGrant.NOTIFICATIONS, card.missing)
        assertEquals(R.string.whatsapp_notifications_off, card.state)
    }

    @Test
    fun `with notices but no file access, file access is asked`() {
        val card = WhatsAppPanel.card(
            listenerOn = true,
            listenerConnected = true,
            storageOn = false,
            contactsOn = true,
            sightings = emptyList()
        )

        assertEquals(WhatsAppGrant.ALL_FILES, card.missing)
        assertEquals(R.string.whatsapp_storage_off, card.state)
    }

    @Test
    fun `everything granted counts what was seen and found, and shows the last few`() {
        val seen = (1..8).map { sighting(it, found = it % 2 == 0) }

        val card = WhatsAppPanel.card(
            listenerOn = true,
            listenerConnected = true,
            storageOn = true,
            contactsOn = true,
            sightings = seen
        )

        assertNull(card.missing)
        assertEquals(R.string.whatsapp_watching, card.state)
        assertEquals(8, card.seen)
        assertEquals(4, card.found)
        assertEquals(WhatsAppPanel.SHOWN, card.shown.size)
    }

    @Test
    fun `everything granted and nothing seen says how to try it`() {
        val card = WhatsAppPanel.card(
            listenerOn = true,
            listenerConnected = true,
            storageOn = true,
            contactsOn = true,
            sightings = emptyList()
        )

        assertEquals(R.string.whatsapp_none_yet, card.state)
    }

    @Test
    fun `contacts come last, since without them documents are still found, only unnumbered`() {
        val card = WhatsAppPanel.card(
            listenerOn = true,
            listenerConnected = true,
            storageOn = true,
            contactsOn = false,
            sightings = emptyList(),
        )

        assertEquals(WhatsAppGrant.CONTACTS, card.missing)
        assertEquals(R.string.whatsapp_contacts_off, card.state)
    }

    @Test
    fun `access on but the listener unbound is said, since nothing is watched then`() {
        val card = WhatsAppPanel.card(
            listenerOn = true,
            listenerConnected = false,
            storageOn = true,
            contactsOn = true,
            sightings = emptyList(),
        )

        assertEquals(WhatsAppGrant.DISCONNECTED, card.missing)
        assertEquals(R.string.whatsapp_disconnected, card.state)
    }
}

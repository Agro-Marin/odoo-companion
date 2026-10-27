package com.odoocompanion.whatsapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactNumbersTest {
    @Test
    fun `WhatsApp's own JID is the number, before any phone the contact lists`() {
        assertEquals(
            "5215512345678",
            ContactNumbers.choose(
                whatsappIds = listOf("5215512345678@s.whatsapp.net"),
                phones = listOf("+52 33 1111 2222"),
            ),
        )
    }

    @Test
    fun `without WhatsApp's row, the contact's one line`() {
        assertEquals(
            "525512345678",
            ContactNumbers.choose(emptyList(), listOf("+52 55 1234 5678")),
        )
    }

    @Test
    fun `one line written two ways is one number, kept in its fullest form`() {
        assertEquals(
            "5215512345678",
            ContactNumbers.choose(emptyList(), listOf("55 1234 5678", "+52 1 55 1234 5678")),
        )
    }

    @Test
    fun `two lines under one name are not guessed between`() {
        assertNull(ContactNumbers.choose(emptyList(), listOf("55 1234 5678", "33 1111 2222")))
        assertNull(
            ContactNumbers.choose(
                listOf("5215512345678@s.whatsapp.net", "5213311112222@s.whatsapp.net"),
                emptyList(),
            ),
        )
    }

    @Test
    fun `two WhatsApp rows that disagree fall back to the phones only if those agree`() {
        assertEquals(
            "525512345678",
            ContactNumbers.choose(
                listOf("5215512345678@s.whatsapp.net", "5213311112222@s.whatsapp.net"),
                listOf("+52 55 1234 5678"),
            ),
        )
    }

    @Test
    fun `a short extension or a lid is no number`() {
        assertNull(ContactNumbers.choose(listOf("123456789012345@lid"), listOf("1234")))
    }

    private fun notice(sender: String, phone: String? = null) = DocumentNotice(
        app = WhatsAppNotices.WHATSAPP,
        chat = sender,
        sender = sender,
        phone = phone,
        label = "CSF.pdf",
        at = 1L,
        isGroup = false,
    )

    @Test
    fun `notices without a number take the contacts', each name asked once`() {
        val asked = mutableListOf<String>()
        val numbered = WhatsAppNotices.withNumbers(
            listOf(notice("Yop"), notice("Yop"), notice("Juan", phone = "521999")),
        ) { name ->
            asked += name
            "5215512345678".takeIf { name == "Yop" }
        }

        assertEquals(listOf("5215512345678", "5215512345678", "521999"), numbered.map { it.phone })
        assertEquals(listOf("Yop"), asked)
    }
}

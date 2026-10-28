package com.odoocompanion.whatsapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppNoticesTest {
    private val page = "📄"

    private fun notice(
        vararg messages: NoticeMessage,
        packageName: String = WhatsAppNotices.WHATSAPP,
        title: String? = "Agroquímicos del Bajío",
        conversationTitle: String? = null,
        isGroup: Boolean = false,
        isSummary: Boolean = false,
        chatKeys: List<String?> = emptyList(),
    ) = NoticeFields(
        packageName = packageName,
        title = title,
        conversationTitle = conversationTitle,
        isGroup = isGroup,
        isSummary = isSummary,
        messages = messages.toList(),
        postedAt = 5_000L,
        chatKeys = chatKeys,
    )

    @Test
    fun `a document message becomes a notice named by its file`() {
        val found = WhatsAppNotices.documents(
            notice(NoticeMessage("$page Constancia.pdf", "Agroquímicos del Bajío", time = 4_000L)),
        )

        assertEquals(1, found.size)
        assertEquals("Constancia.pdf", found.single().label)
        assertEquals("Agroquímicos del Bajío", found.single().chat)
        assertEquals(4_000L, found.single().at)
    }

    @Test
    fun `text, photos and other apps are not documents`() {
        assertTrue(
            WhatsAppNotices.documents(notice(NoticeMessage("Hola, buen día", "A"))).isEmpty(),
        )
        assertTrue(
            WhatsAppNotices.documents(notice(NoticeMessage("📷 Foto", "A"))).isEmpty(),
        )
        assertTrue(
            WhatsAppNotices.documents(
                notice(NoticeMessage("$page CSF.pdf", "A"), packageName = "org.telegram.messenger"),
            ).isEmpty(),
        )
    }

    @Test
    fun `WhatsApp Business is read like WhatsApp`() {
        val found = WhatsAppNotices.documents(
            notice(
                NoticeMessage("$page CSF.pdf", "A"),
                packageName = WhatsAppNotices.WHATSAPP_BUSINESS,
            ),
        )

        assertEquals(WhatsAppNotices.WHATSAPP_BUSINESS, found.single().app)
    }

    @Test
    fun `a summary repeats its children and is skipped`() {
        val found =
            WhatsAppNotices.documents(notice(NoticeMessage("$page CSF.pdf", "A"), isSummary = true))

        assertTrue(found.isEmpty())
    }

    @Test
    fun `a document this handset sent is not one a customer sent`() {
        val found =
            WhatsAppNotices.documents(
                notice(NoticeMessage("$page Cotización.pdf", null, fromMe = true)),
            )

        assertTrue(found.isEmpty())
    }

    @Test
    fun `the same document carried by two messages of one notice is one notice`() {
        val message = NoticeMessage("$page CSF.pdf", "A", time = 4_000L)

        assertEquals(1, WhatsAppNotices.documents(notice(message, message)).size)
    }

    @Test
    fun `a group names the chat and the sender apart`() {
        val found = WhatsAppNotices.documents(
            notice(
                NoticeMessage("$page CSF.pdf", "Juan Pérez"),
                title = "Clientes Norte: Juan Pérez",
                conversationTitle = "Clientes Norte",
                isGroup = true,
            ),
        ).single()

        assertEquals("Clientes Norte", found.chat)
        assertEquals("Juan Pérez", found.sender)
        assertTrue(found.isGroup)
    }

    @Test
    fun `a message without its own time takes the notice's`() {
        val found = WhatsAppNotices.documents(notice(NoticeMessage("$page CSF.pdf", "A"))).single()

        assertEquals(5_000L, found.at)
    }

    @Test
    fun `a page count after the name is not part of it`() {
        assertEquals("CSF 2026.pdf", WhatsAppNotices.labelOf("$page CSF 2026.pdf (3 páginas)"))
        assertEquals("CSF.pdf", WhatsAppNotices.labelOf("  $page   CSF.pdf (1 page)"))
        assertEquals("CSF (1).pdf", WhatsAppNotices.labelOf("$page CSF (1).pdf"))
    }

    @Test
    fun `the mark alone names nothing`() {
        assertNull(WhatsAppNotices.labelOf(page))
        assertNull(WhatsAppNotices.labelOf(null))
    }

    @Test
    fun `a page count behind a no-break space or a direction mark is not part of it`() {
        val nbsp = "\u00A0"
        val lrm = "\u200E"

        // The label a handset showed: "CSF PCA221013LF1 2026-09.pdf (3 páginas)".
        assertEquals(
            "CSF PCA221013LF1 2026-09.pdf",
            WhatsAppNotices.labelOf("$page CSF PCA221013LF1 2026-09.pdf$nbsp(3${nbsp}páginas)"),
        )
        assertEquals(
            "CSF.pdf",
            WhatsAppNotices.labelOf("$page $lrm" + "CSF.pdf$lrm (1 página)"),
        )
    }

    @Test
    fun `a one-to-one chat's number comes from its JID when the person names no number`() {
        val found = WhatsAppNotices.documents(
            notice(
                NoticeMessage("$page CSF.pdf", "Yop", senderKey = "1"),
                chatKeys = listOf("5215512345678@s.whatsapp.net", null),
            ),
        ).single()

        assertEquals("5215512345678", found.phone)
    }

    @Test
    fun `a group's JID is not the sender's number`() {
        val found = WhatsAppNotices.documents(
            notice(
                NoticeMessage("$page CSF.pdf", "Juan"),
                conversationTitle = "Clientes Norte",
                isGroup = true,
                chatKeys = listOf("5215512345678@s.whatsapp.net"),
            ),
        ).single()

        assertNull(found.phone)
    }

    @Test
    fun `a number comes from a tel uri or a WhatsApp jid, and from nothing else`() {
        assertEquals("5215512345678", WhatsAppNotices.phoneOf(null, "tel:+52 1 55 1234 5678"))
        assertEquals("5215512345678", WhatsAppNotices.phoneOf("5215512345678@s.whatsapp.net", null))
        assertNull(WhatsAppNotices.phoneOf("Juan Pérez", null))
        assertNull(WhatsAppNotices.phoneOf("120363040000000000@g.us", null))
        assertNull(WhatsAppNotices.phoneOf(null, "content://contacts/people/12"))
        assertNull(WhatsAppNotices.phoneOf(null, "tel:123"))
    }
}

package com.odoocompanion.whatsapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WhatsAppDocumentsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val inbox = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents"

    private fun saved(path: String, modifiedAt: Long = 1_000_000L): File {
        val file = File(folder.root, path)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(16))
        file.setLastModified(modifiedAt)
        return file
    }

    private fun names() = WhatsAppDocuments(folder.root).scan().map { it.name }.sorted()

    @Test
    fun `received documents are listed from both apps and the legacy folder`() {
        saved("$inbox/CSF.pdf")
        saved(
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Documents/Factura.xml",
        )
        saved("WhatsApp/Media/WhatsApp Documents/Viejo.pdf")

        assertEquals(listOf("CSF.pdf", "Factura.xml", "Viejo.pdf"), names())
    }

    @Test
    fun `what this phone sent, private files and hidden files are not received documents`() {
        saved("$inbox/Sent/Cotización.pdf")
        saved("$inbox/Private/Nómina.pdf")
        saved("$inbox/.nomedia")

        assertEquals(emptyList<String>(), names())
    }

    @Test
    fun `no WhatsApp folder is an empty listing, not a failure`() {
        assertEquals(emptyList<String>(), names())
    }

    private val at = 10_000_000L

    private fun notice(label: String) = DocumentNotice(
        app = WhatsAppNotices.WHATSAPP,
        chat = "A",
        sender = "A",
        phone = null,
        label = label,
        at = at,
        isGroup = false,
    )

    private fun file(name: String, modifiedAt: Long) =
        DocumentFile("/w/$name", name, modifiedAt, 16)

    @Test
    fun `a notice takes the file of its name, or its numbered twin`() {
        val files = listOf(file("Otro.pdf", at), file("CSF (1).pdf", at + 5_000))

        assertEquals(
            "CSF (1).pdf",
            DocumentMatcher.match(notice("CSF.pdf"), files, emptySet())?.name,
        )
    }

    @Test
    fun `of two files of that name, the one written nearest the notice`() {
        val files = listOf(file("CSF.pdf", at - 60_000), file("CSF (1).pdf", at + 2_000))

        assertEquals(
            "CSF (1).pdf",
            DocumentMatcher.match(notice("CSF.pdf"), files, emptySet())?.name,
        )
    }

    @Test
    fun `a file another notice already took is not taken twice`() {
        val files = listOf(file("CSF.pdf", at), file("CSF (1).pdf", at + 60_000))

        assertEquals(
            "CSF (1).pdf",
            DocumentMatcher.match(notice("CSF.pdf"), files, setOf("/w/CSF.pdf"))?.name,
        )
    }

    @Test
    fun `a file of that name from long before the notice is an older document`() {
        val files = listOf(file("CSF.pdf", at - DocumentMatcher.BEFORE_NOTICE_MILLIS - 1))

        assertNull(DocumentMatcher.match(notice("CSF.pdf"), files, emptySet()))
    }

    @Test
    fun `a document downloaded days later by hand is still its notice's`() {
        val files = listOf(file("CSF.pdf", at + 3L * 24 * 60 * 60 * 1000))

        assertEquals("CSF.pdf", DocumentMatcher.match(notice("CSF.pdf"), files, emptySet())?.name)
    }

    @Test
    fun `a name is matched whatever its case, and dots in it are not wildcards`() {
        val files = listOf(file("csfX2026.PDF", at), file("csf.2026.pdf", at + 1))

        assertEquals(
            "csf.2026.pdf",
            DocumentMatcher.match(notice("CSF.2026.pdf"), files, emptySet())?.name,
        )
    }

    @Test
    fun `a caption takes only a file written at the notice's own moment`() {
        val near = file("scan0001.pdf", at + 30_000)
        val far = file("scan0002.pdf", at + DocumentMatcher.CAPTION_SLACK_MILLIS + 1)

        assertEquals(
            near,
            DocumentMatcher.match(notice("Mi constancia"), listOf(far, near), emptySet()),
        )
        assertNull(DocumentMatcher.match(notice("Mi constancia"), listOf(far), emptySet()))
    }

    @Test
    fun `the folders to watch are those the scan reads, and not what this phone sent`() {
        saved("$inbox/CSF.pdf")
        saved("$inbox/Sent/Cotización.pdf")
        saved("$inbox/2026/Factura.pdf")

        val watched = WhatsAppDocuments(folder.root).watchedDirectories()
            .map { it.relativeTo(folder.root).path }
            .sorted()

        assertEquals(listOf(inbox, "$inbox/2026"), watched)
    }
}

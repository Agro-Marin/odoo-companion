package com.odoocompanion.whatsapp

import com.odoocompanion.net.DocumentMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UploadedNoticeTest {
    private fun metadata(kind: String?, fileName: String = "CSF PCA221013LF1.pdf", at: Long = 1L) =
        DocumentMetadata(
            fileName = fileName,
            mimetype = "application/pdf",
            kind = kind,
            rfc = "PCA221013LF1",
            chat = "Yop",
            receivedAt = at,
        )

    @Test
    fun `the notice says what the handset read the document as`() {
        val notice = UploadedNotice.of(metadata("csf"))

        assertEquals(DocumentKind.CSF, notice.kind)
        assertEquals("CSF PCA221013LF1.pdf", notice.fileName)
        assertEquals("PCA221013LF1", notice.rfc)
        assertEquals("Yop", notice.chat)
        assertEquals(DocumentKind.PASSPORT, UploadedNotice.of(metadata("passport")).kind)
    }

    @Test
    fun `a kind this build does not know is a plain document`() {
        assertNull(UploadedNotice.of(metadata("acta")).kind)
        assertNull(UploadedNotice.of(metadata(null)).kind)
    }

    // The same document twice replaces its notice; two documents are two.
    @Test
    fun `one notice id per document`() {
        assertEquals(UploadedNotice.idOf(metadata("csf")), UploadedNotice.idOf(metadata("csf")))
        assertNotEquals(
            UploadedNotice.idOf(metadata("csf")),
            UploadedNotice.idOf(metadata("ine", fileName = "INE.pdf", at = 2L)),
        )
    }
}

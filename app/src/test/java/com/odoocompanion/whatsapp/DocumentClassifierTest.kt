package com.odoocompanion.whatsapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentClassifierTest {
    private fun kindOf(text: String, qr: List<String> = emptyList()) =
        DocumentClassifier.classify(text, qr).kind

    @Test
    fun `a constancia's own title makes it one, and its RFC is read off the page`() {
        val read = DocumentClassifier.classify(
            "CÉDULA DE IDENTIFICACIÓN FISCAL\nCONSTANCIA DE SITUACIÓN FISCAL\n" +
                "RFC: PCA221013LF1\nDenominación/Razón Social: PRODUCTOS DEL CAMPO",
            emptyList(),
        )

        assertEquals(DocumentKind.CSF, read.kind)
        assertEquals("PCA221013LF1", read.rfc)
    }

    @Test
    fun `OCR noise in the title does not hide it`() {
        assertEquals(DocumentKind.CSF, kindOf("C0NSTANCIA  DE SITUACION\nFISCAL"))
    }

    @Test
    fun `the SAT's QR alone is a constancia, and its RFC wins over the text's`() {
        val read = DocumentClassifier.classify(
            "texto ilegible de una foto",
            listOf(
                "https://siat.sat.gob.mx/app/qr/faces/pages/mobile/validadorqr.jsf" +
                    "?D1=10&D2=1&D3=22091234567_PCA221013LF1",
            ),
        )

        assertEquals(DocumentKind.CSF, read.kind)
        assertEquals("PCA221013LF1", read.rfc)
    }

    @Test
    fun `an INE is told by its issuer, its title or its machine-readable zone`() {
        assertEquals(DocumentKind.INE, kindOf("INSTITUTO NACIONAL ELECTORAL\nMÉXICO"))
        assertEquals(DocumentKind.INE, kindOf("CREDENCIAL PARA VOTAR\nNOMBRE"))
        assertEquals(DocumentKind.INE, kindOf("INSTITUTO FEDERAL ELECTORAL"))
        assertEquals(DocumentKind.INE, kindOf("garbled\nIDMEX1234567890<<1234\n"))
    }

    @Test
    fun `a passport is told by its word or its MRZ, however OCR reads the filler`() {
        assertEquals(DocumentKind.PASSPORT, kindOf("ESTADOS UNIDOS MEXICANOS\nPASAPORTE"))
        assertEquals(DocumentKind.PASSPORT, kindOf("P<MEXPEREZ<GARCIA<<JUAN<<<<<<<<<"))
        assertEquals(DocumentKind.PASSPORT, kindOf("PKUSASMITH<<JOHN<<<<<<<<<<<<<<"))
    }

    @Test
    fun `everything else is other, and only other stays on the handset`() {
        val read = DocumentClassifier.classify("COTIZACIÓN 1234\nTotal: $5,000.00", emptyList())

        assertEquals(DocumentKind.OTHER, read.kind)
        assertNull(read.rfc)
        assertFalse(DocumentKind.OTHER.uploads)
        assertTrue(DocumentKind.CSF.uploads && DocumentKind.INE.uploads)
        assertTrue(DocumentKind.PASSPORT.uploads)
    }

    @Test
    fun `an invoice naming an RFC is not a constancia`() {
        assertEquals(DocumentKind.OTHER, kindOf("FACTURA\nRFC emisor: PCA221013LF1"))
    }

    @Test
    fun `a QR that is not the SAT's makes nothing a constancia`() {
        assertEquals(
            DocumentKind.OTHER,
            kindOf("", listOf("https://example.com/?D3=1_PCA221013LF1"))
        )
    }
}

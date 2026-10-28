package com.odoocompanion.whatsapp

import kotlinx.serialization.Serializable
import java.text.Normalizer

// The documents that leave the handset. Everything else WhatsApp brings stays
// on it: the decision is made here, before an upload, so a quote or a private
// photo is never sent to find out what it was.
enum class DocumentKind {
    CSF,
    INE,
    PASSPORT,
    OTHER,
    ;

    val uploads: Boolean
        get() = this != OTHER
}

@Serializable
data class Classification(val kind: DocumentKind, val rfc: String? = null)

// What a page says it is, read from its OCR text and the QR codes on it. The
// text is an OCR's, so it is compared with accents, case, spacing and
// punctuation stripped: "CONSTANCIA DE SITUACIÓN FISCAL" read as
// "C0NSTANCIA DE SITUACION  FISCAL" must still be one.
object DocumentClassifier {
    // The SAT's validation link on every constancia, carrying "idCIF_RFC" in D3.
    private val SAT_QR = Regex("""siat\.sat\.gob\.mx.*[?&]D3=\d+_([A-Z&Ñ]{3,4}\d{6}[A-Z0-9]{3})""")

    private val RFC = Regex("""\b([A-Z&Ñ]{3,4}\d{6}[A-Z0-9]{3})\b""")

    // The machine-readable zone. ICAO 9303: a passport's first line opens with
    // "P" and its type, an ID card's with "I" -- the INE's is "IDMEX". OCR
    // reads the filler "<" as "<", "«" or "K"; the country code still follows.
    private val PASSPORT_MRZ = Regex("""(?m)^P[<«K][A-Z]{3}[A-Z<«]{5,}""")
    private val INE_MRZ = Regex("""(?m)^IDMEX\d""")

    private val CSF_WORDS = listOf("CONSTANCIADESITUACIONFISCAL", "CEDULADEIDENTIFICACIONFISCAL")
    private val INE_WORDS = listOf(
        "INSTITUTONACIONALELECTORAL",
        "INSTITUTOFEDERALELECTORAL",
        "CREDENCIALPARAVOTAR",
    )
    private val PASSPORT_WORDS = listOf("PASAPORTE", "PASSPORT")

    fun classify(text: String, qrCodes: List<String>): Classification {
        val upper = text.uppercase()
        val letters = lettersOf(upper)
        val satQr = qrCodes.firstNotNullOfOrNull { SAT_QR.find(it)?.groupValues?.get(1) }
        if (satQr != null || CSF_WORDS.any { it in letters }) {
            return Classification(DocumentKind.CSF, satQr ?: RFC.find(upper)?.groupValues?.get(1))
        }
        // A passport before an INE: the INE carries no passport word, while a
        // passport page may well name the elector credential it was issued on.
        if (PASSPORT_MRZ.containsMatchIn(upper) || PASSPORT_WORDS.any { it in letters }) {
            return Classification(DocumentKind.PASSPORT)
        }
        if (INE_MRZ.containsMatchIn(upper) || INE_WORDS.any { it in letters }) {
            return Classification(DocumentKind.INE)
        }
        return Classification(DocumentKind.OTHER)
    }

    private fun lettersOf(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace('0', 'O')
        .filter { it in 'A'..'Z' }
}

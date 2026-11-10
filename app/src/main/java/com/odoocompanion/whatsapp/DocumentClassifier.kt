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
// text is an OCR's, so it is compared with accents, case and punctuation
// stripped, and a phrase may lose the spaces between its words:
// "CONSTANCIA DE SITUACIÓN FISCAL" read as "C0NSTANCIA DE SITUACION  FISCAL" or
// "CONSTANCIADESITUACION FISCAL" is still the title. A word may not be cut out
// of a longer one, and a title alone is not the document: a contract asking for
// "identificación oficial (INE o pasaporte)", a supplier's letter asking for the
// constancia, or "COMPAS SPORT" run together into "...PASSPORT" all used to
// upload. Each kind needs what only the document itself prints.
object DocumentClassifier {
    // The SAT's validation link on every constancia, carrying "idCIF_RFC" in D3.
    private val SAT_QR = Regex("""siat\.sat\.gob\.mx.*[?&]D3=\d+_([A-Z&Ñ]{3,4}\d{6}[A-Z0-9]{3})""")

    private val RFC = Regex("""\b([A-Z&Ñ]{3,4}\d{6}[A-Z0-9]{3})\b""")

    private val CURP = Regex("""\b[A-Z]{4}\d{6}[HM][A-Z]{5}[A-Z0-9]\d\b""")

    // The machine-readable zone. ICAO 9303: a passport's first line opens with
    // "P" and its type, an ID card's with "I" -- the INE's is "IDMEX". OCR
    // reads the filler "<" as "<", "«" or "K"; the country code still follows.
    private val PASSPORT_MRZ = Regex("""(?m)^P[<«K][A-Z]{3}[A-Z<«]{5,}""")
    private val INE_MRZ = Regex("""(?m)^IDMEX\d""")

    private val ACCENTS = Regex("""\p{Mn}+""")

    // A constancia's page carries its title, the cédula's header and the idCIF.
    private val CSF_MARKS = listOf(
        phrase("CONSTANCIA", "DE", "SITUACION", "FISCAL"),
        phrase("CEDULA", "DE", "IDENTIFICACION", "FISCAL"),
        phrase("IDCIF"),
    )

    // An INE's front names its issuer, its title and the elector's key, and
    // prints a CURP.
    private val INE_MARKS = listOf(
        phrase("INSTITUTO", "NACIONAL", "ELECTORAL"),
        phrase("INSTITUTO", "FEDERAL", "ELECTORAL"),
        phrase("CREDENCIAL", "PARA", "VOTAR"),
        phrase("CLAVE", "DE", "ELECTOR"),
    )

    private val PASSPORT_TITLES = listOf(phrase("PASAPORTE"), phrase("PASSPORT"))

    // A passport's data page labels its fields in English as well, which a
    // Spanish letter that mentions a passport does not.
    private val PASSPORT_FIELDS = listOf(
        phrase("SURNAME"),
        phrase("GIVEN", "NAMES"),
        phrase("NATIONALITY"),
        phrase("DATE", "OF", "BIRTH"),
        phrase("PLACE", "OF", "BIRTH"),
        phrase("DATE", "OF", "ISSUE"),
        phrase("DATE", "OF", "EXPIRY"),
    )

    fun classify(text: String, qrCodes: List<String>): Classification {
        val upper = text.uppercase()
        val words = wordsOf(upper)
        fun count(marks: List<Regex>) = marks.count { it.containsMatchIn(words) }

        val satQr = qrCodes.firstNotNullOfOrNull { SAT_QR.find(it)?.groupValues?.get(1) }
        if (satQr != null || count(CSF_MARKS) >= 2) {
            return Classification(DocumentKind.CSF, satQr ?: RFC.find(upper)?.groupValues?.get(1))
        }
        // A passport before an INE: the INE carries no passport word, while a
        // passport page may well name the elector credential it was issued on.
        if (PASSPORT_MRZ.containsMatchIn(upper) ||
            (count(PASSPORT_TITLES) > 0 && count(PASSPORT_FIELDS) > 0)
        ) {
            return Classification(DocumentKind.PASSPORT)
        }
        val ineMarks = count(INE_MARKS) + if (CURP.containsMatchIn(upper)) 1 else 0
        if (INE_MRZ.containsMatchIn(upper) || ineMarks >= 2) {
            return Classification(DocumentKind.INE)
        }
        return Classification(DocumentKind.OTHER)
    }

    // Letters only, accents dropped, one space between runs of them; a zero is
    // OCR's O.
    private fun wordsOf(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(ACCENTS, "")
        .replace('0', 'O')
        .map { if (it in 'A'..'Z') it else ' ' }
        .joinToString("")
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ")

    // The words in order, the space between two of them optional, never part
    // of a longer word at either end.
    private fun phrase(vararg words: String): Regex =
        Regex("(?<![A-Z])" + words.joinToString(" ?") + "(?![A-Z])")
}

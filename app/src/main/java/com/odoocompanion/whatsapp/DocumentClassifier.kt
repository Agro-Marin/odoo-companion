package com.odoocompanion.whatsapp

import kotlinx.serialization.Serializable
import java.text.Normalizer

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

object DocumentClassifier {
    private val SAT_QR = Regex("""siat\.sat\.gob\.mx.*[?&]D3=\d+_([A-Z&Ñ]{3,4}\d{6}[A-Z0-9]{3})""")

    private val RFC = Regex("""\b([A-Z&Ñ]{3,4}\d{6}[A-Z0-9]{3})\b""")

    private val CURP = Regex("""\b[A-Z]{4}\d{6}[HM][A-Z]{5}[A-Z0-9]\d\b""")

    private val PASSPORT_MRZ = Regex("""(?m)^P[<«K][A-Z]{3}[A-Z<«]{5,}""")
    private val INE_MRZ = Regex("""(?m)^IDMEX\d""")

    private val ACCENTS = Regex("""\p{Mn}+""")

    private val CSF_MARKS = listOf(
        phrase("CONSTANCIA", "DE", "SITUACION", "FISCAL"),
        phrase("CEDULA", "DE", "IDENTIFICACION", "FISCAL"),
        phrase("IDCIF"),
    )

    private val INE_MARKS = listOf(
        phrase("INSTITUTO", "NACIONAL", "ELECTORAL"),
        phrase("INSTITUTO", "FEDERAL", "ELECTORAL"),
        phrase("CREDENCIAL", "PARA", "VOTAR"),
        phrase("CLAVE", "DE", "ELECTOR"),
    )

    private val PASSPORT_TITLES = listOf(phrase("PASAPORTE"), phrase("PASSPORT"))

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

    private fun wordsOf(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(ACCENTS, "")
        .replace('0', 'O')
        .map { if (it in 'A'..'Z') it else ' ' }
        .joinToString("")
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ")

    private fun phrase(vararg words: String): Regex =
        Regex("(?<![A-Z])" + words.joinToString(" ?") + "(?![A-Z])")
}

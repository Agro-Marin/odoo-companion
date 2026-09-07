package com.odoocompanion.recording

import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

data class ParsedRecording(val number: String?, val recordedAtMillis: Long)

object RecordingFilename {
    fun of(file: File, modifiedAt: Long = file.lastModified()): ParsedRecording {
        val tokens = DIGIT_TOKEN.findAll(file.nameWithoutExtension).map { it.value }.toList()
        val number = tokens.firstOrNull { it.startsWith("+") }
            ?: tokens.firstOrNull { it.length in 10..15 && !namesWriteTime(it, modifiedAt) }
            ?: tokens.firstOrNull { it.length in 7..9 && !namesWriteTime(it, modifiedAt) }
        return ParsedRecording(number, modifiedAt)
    }

    private fun namesWriteTime(token: String, modifiedAt: Long): Boolean {
        val span = calendarSpan(token) ?: epochSpan(token) ?: return false
        val (start, length) = span
        return modifiedAt >= start - SLACK_MILLIS && modifiedAt <= start + length + SLACK_MILLIS
    }

    private fun calendarSpan(token: String): Pair<Long, Long>? {
        if (!CALENDAR_TOKEN.matches(token)) return null
        fun part(from: Int, absent: Int) =
            if (from + 2 <= token.length) token.substring(from, from + 2).toInt() else absent
        val calendar = Calendar.getInstance().apply {
            clear()
            set(
                token.substring(0, 4).toInt(),
                part(4, 1) - 1,
                part(6, 1),
                part(8, 0),
                part(10, 0),
                part(12, 0),
            )
        }
        return calendar.timeInMillis to PRECISION_MILLIS.getValue(token.length)
    }

    private fun epochSpan(token: String): Pair<Long, Long>? = when (token.length) {
        10 -> TimeUnit.SECONDS.toMillis(token.toLong()) to 0L
        13 -> token.toLong() to 0L
        else -> null
    }

    private val DIGIT_TOKEN = Regex("""\+?\d{6,15}""")

    private val CALENDAR_TOKEN =
        Regex("""(19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])(\d{2}){0,3}""")

    private val PRECISION_MILLIS = mapOf(
        8 to TimeUnit.DAYS.toMillis(1),
        10 to TimeUnit.HOURS.toMillis(1),
        12 to TimeUnit.MINUTES.toMillis(1),
        14 to TimeUnit.SECONDS.toMillis(1),
    )

    private val SLACK_MILLIS = TimeUnit.HOURS.toMillis(6)
}

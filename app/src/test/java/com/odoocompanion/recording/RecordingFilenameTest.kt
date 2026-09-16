package com.odoocompanion.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Calendar

class RecordingFilenameTest {
    // 40 days past any stamp used in these names.
    private val movedLater = 1_800_000_000_000L

    @get:Rule
    val folder = TemporaryFolder()

    private fun file(name: String): File = folder.newFile(name)

    @Test
    fun `number first then date and time`() {
        val parsed = RecordingFilename.of(file("Call recording +525512345678_251028_143501.m4a"))

        assertEquals("+525512345678", parsed.number)
    }

    @Test
    fun `date and time first does not mistake the date for the number`() {
        val parsed = RecordingFilename.of(file("20251028_143501_5512345678.amr"))

        assertEquals("5512345678", parsed.number)
    }

    @Test
    fun `a six digit date and time pair alone yields no number`() {
        assertNull(RecordingFilename.of(file("Call_251028_143501.m4a")).number)
    }

    @Test
    fun `a bare number still parses`() {
        assertEquals("5512345678", RecordingFilename.of(file("call_5512345678.m4a")).number)
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    @Test
    fun `an unseparated date and time is not a phone number`() {
        val written = at(2025, 10, 28, 14, 35)

        assertNull(RecordingFilename.of(file("Call_20251028143501.m4a"), written).number)
        assertNull(RecordingFilename.of(file("20251028143501.amr"), written).number)
        assertNull(RecordingFilename.of(file("rec_202510281435.m4a"), written).number)
        assertNull(RecordingFilename.of(file("Call_20251028.m4a"), written).number)
    }

    @Test
    fun `a number that merely starts like a year still parses`() {
        assertEquals(
            "2012345678",
            RecordingFilename.of(file("call_2012345678.m4a"), at(2025, 10, 28, 14, 35)).number,
        )
    }

    @Test
    fun `digits that read as a date but not this file's date are a number`() {
        assertEquals(
            "2025102814",
            RecordingFilename.of(file("call_2025102814.m4a"), at(2026, 8, 28, 9, 0)).number,
        )
    }

    @Test
    fun `an epoch stamp naming the write time is not a phone number`() {
        assertNull(RecordingFilename.of(file("1730123701234.m4a"), 1_730_123_701_234L).number)
        assertEquals(
            "1730123701234",
            RecordingFilename.of(file("call_1730123701234.m4a"), at(2026, 8, 28, 9, 0)).number,
        )
    }

    @Test
    fun `an unrecognised name yields no number and falls back to the file time`() {
        val target = file("voice-memo.m4a")

        val parsed = RecordingFilename.of(target)

        assertNull(parsed.number)
        assertEquals(target.lastModified(), parsed.recordedAtMillis)
    }

    // Every case below is the same file after its write time stopped agreeing
    // with the stamp in its name: copied off the handset, restored from a
    // backup, or touched by a file manager. Before this, all three uploaded the
    // stamp as the caller's number.
    private val longAfterTheStamp = movedLater

    @Test
    fun `a stamp is not the caller even when the file was written much later`() {
        val parsed = RecordingFilename.of(
            File("20260915143022_5512345678.m4a"),
            longAfterTheStamp,
        )

        assertEquals("5512345678", parsed.number)
    }

    @Test
    fun `a name that is only a stamp yields no number rather than inventing one`() {
        val parsed = RecordingFilename.of(
            File("Call recording 20260915143022.m4a"),
            longAfterTheStamp,
        )

        assertNull(parsed.number)
    }

    @Test
    fun `a stamp down to the minute is a stamp too`() {
        val parsed = RecordingFilename.of(File("grabacion_202609151430.amr"), longAfterTheStamp)

        assertNull(parsed.number)
    }

    // The rule is the shape, and it has to stop where the shape stops being
    // certain: fourteen digits that are not a real clock are just digits.
    @Test
    fun `fourteen digits with an impossible hour are still a number`() {
        val parsed = RecordingFilename.of(File("20260915993022.m4a"), longAfterTheStamp)

        assertEquals("20260915993022", parsed.number)
    }

    @Test
    fun `a ten digit number that starts like a year is left alone`() {
        val parsed = RecordingFilename.of(File("2012251430_llamada.m4a"), longAfterTheStamp)

        assertEquals("2012251430", parsed.number)
    }
}

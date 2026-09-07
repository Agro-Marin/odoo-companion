package com.odoocompanion.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LoggablePathTest {
    // The names an OEM dialer actually writes, from RecordingFilenameTest's corpus.
    private val realNames = listOf(
        "/storage/emulated/0/Recordings/Call/Call recording +525512345678_251028_143501.m4a",
        "/storage/emulated/0/Recordings/Call/call_5512345678.m4a",
        "/storage/emulated/0/MIUI/sound_recorder/call_rec/call_2012345678.m4a",
        "/storage/emulated/0/Sounds/Call/+52 55 1234 5678 20251028.amr",
    )

    @Test
    fun `no digit of the counterparty's number survives`() {
        for (path in realNames) {
            val logged = path.loggableDirectory()
            assertFalse(
                "a phone number reached the log line from $path",
                logged.any { it.isDigit() && logged.substringAfterLast('/').contains(it) } &&
                    logged.substringAfterLast('/').count { it.isDigit() } >= 7,
            )
            assertFalse("the filename itself must not survive", logged.contains(".m4a"))
            assertFalse("nor any other extension", logged.contains(".amr"))
        }
    }

    @Test
    fun `the per-OEM folder does survive, because that is the diagnostic`() {
        assertEquals(
            "/storage/emulated/0/Recordings/Call",
            realNames[0].loggableDirectory(),
        )
        assertEquals(
            "/storage/emulated/0/MIUI/sound_recorder/call_rec",
            realNames[2].loggableDirectory(),
        )
    }

    @Test
    fun `a bare filename with no folder yields no filename either`() {
        assertEquals("an unknown folder", "call_5512345678.m4a".loggableDirectory())
    }

    @Test
    fun `an empty path is named rather than logged as nothing`() {
        assertEquals("an unknown folder", "".loggableDirectory())
    }
}

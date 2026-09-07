package com.odoocompanion.net

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Test

class PayloadContractTest {
    @Test
    fun `a full location fix`() {
        assertEquals(
            """{"latitude":19.4326,"longitude":-99.1332,"timestamp":1751000000000,""" +
                """"accuracy":8.0,"altitude":2240.0,"speed":3.4,"heading":275.0,""" +
                """"battery_level":87}""",
            WireJson.encodeToString(
                LocationFix(
                    latitude = 19.4326,
                    longitude = -99.1332,
                    timestamp = 1_751_000_000_000,
                    accuracy = 8.0f,
                    altitude = 2240.0,
                    speed = 3.4f,
                    heading = 275.0f,
                    batteryLevel = 87,
                ),
            ),
        )
    }

    @Test
    fun `a minimal location fix omits what was never measured`() {
        assertEquals(
            """{"latitude":19.0,"longitude":-99.0,"timestamp":1751000000000}""",
            WireJson.encodeToString(
                LocationFix(latitude = 19.0, longitude = -99.0, timestamp = 1_751_000_000_000),
            ),
        )
    }

    @Test
    fun `a call record`() {
        assertEquals(
            """{"number":"+525512345678","direction":"incoming","timestamp":1751000000000,""" +
                """"duration":42,"contact_name":"Ana"}""",
            WireJson.encodeToString(
                CallRecord(
                    number = "+525512345678",
                    direction = "incoming",
                    timestamp = 1_751_000_000_000,
                    duration = 42,
                    contactName = "Ana",
                ),
            ),
        )
    }

    @Test
    fun `a call with no cached name omits contact_name`() {
        assertEquals(
            """{"number":"+52","direction":"outgoing","timestamp":1,"duration":0}""",
            WireJson.encodeToString(
                CallRecord(number = "+52", direction = "outgoing", timestamp = 1, duration = 0),
            ),
        )
    }

    @Test
    fun `recording metadata`() {
        assertEquals(
            """{"number":"+525512345678","recorded_at":1751000000000,""" +
                """"file_name":"call_5512345678.m4a","mimetype":"audio/mp4"}""",
            WireJson.encodeToString(
                RecordingMetadata(
                    number = "+525512345678",
                    recordedAt = 1_751_000_000_000,
                    fileName = "call_5512345678.m4a",
                    mimetype = "audio/mp4",
                ),
            ),
        )
    }

    @Test
    fun `the batch wrappers use the list keys the endpoints read`() {
        val fix = LocationFix(latitude = 1.0, longitude = 2.0, timestamp = 3)
        assertEquals(
            """{"points":[{"latitude":1.0,"longitude":2.0,"timestamp":3}]}""",
            WireJson.encodeToString(LocationUpload(listOf(fix))),
        )
        val call = CallRecord(number = "+1", direction = "incoming", timestamp = 3, duration = 4)
        assertEquals(
            """{"calls":[{"number":"+1","direction":"incoming","timestamp":3,"duration":4}]}""",
            WireJson.encodeToString(CallLogUpload(listOf(call))),
        )
    }

    @Test
    fun `times are epoch milliseconds, not seconds and not ISO`() {
        val encoded = WireJson.encodeToString(
            LocationFix(latitude = 0.0, longitude = 0.0, timestamp = 1_751_000_000_000),
        )
        assertEquals(true, encoded.contains(""""timestamp":1751000000000"""))
    }

    @Test
    fun `an unknown key in a stored payload does not break the drain`() {
        val stored = """{"latitude":1.0,"longitude":2.0,"timestamp":3,"satellites":9}"""

        val fix = WireJson.decodeFromString<LocationFix>(stored)

        assertEquals(1.0, fix.latitude, 0.0)
        assertEquals(3, fix.timestamp)
    }

    @Test
    fun `a call queued by an older build keeps the value it was stored with`() {
        val stored = """{"number":"+1","direction":"1","timestamp":3,"duration":4}"""

        val call = WireJson.decodeFromString<CallRecord>(stored)

        assertEquals("1", call.direction)
        assertEquals(stored, WireJson.encodeToString(call))
    }
}

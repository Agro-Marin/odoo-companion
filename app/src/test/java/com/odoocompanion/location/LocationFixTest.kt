package com.odoocompanion.location

import android.app.Application
import android.location.Location
import com.odoocompanion.location.LocationForegroundService.Companion.fixOf
import com.odoocompanion.net.WireJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LocationFixTest {
    private fun location(block: Location.() -> Unit = {}) = Location("gps").apply {
        latitude = 19.4326
        longitude = -99.1332
        time = 1_751_000_000_000
        accuracy = 8.0f
        block()
    }

    @Test
    fun `the fix time is the location's epoch milliseconds, unchanged`() {
        assertEquals(1_751_000_000_000, fixOf(location(), null).timestamp)
    }

    @Test
    fun `position and accuracy are carried straight through`() {
        val fix = fixOf(location(), batteryLevel = 87)

        assertEquals(19.4326, fix.latitude, 1e-9)
        assertEquals(-99.1332, fix.longitude, 1e-9)
        assertEquals(8.0f, fix.accuracy!!, 1e-6f)
        assertEquals(87, fix.batteryLevel)
    }

    @Test
    fun `readings the handset never took are absent, not zero`() {
        val fix = fixOf(location(), null)

        assertNull(fix.altitude)
        assertNull(fix.speed)
        assertNull(fix.heading)
    }

    @Test
    fun `a genuine zero reading is kept`() {
        val fix = fixOf(
            location {
                altitude = 0.0
                speed = 0.0f
                bearing = 0.0f
            },
            null,
        )

        assertEquals(0.0, fix.altitude!!, 1e-9)
        assertEquals(0.0f, fix.speed!!, 1e-6f)
        assertEquals(0.0f, fix.heading!!, 1e-6f)
    }

    @Test
    fun `speed is passed on in the metres per second Android reports`() {
        val metresPerSecond = 3.4f

        val fix = fixOf(location { speed = metresPerSecond }, batteryLevel = null)

        assertEquals(metresPerSecond, fix.speed!!, 1e-6f)
    }

    @Test
    fun `a measured altitude, speed and bearing are carried through`() {
        val fix = fixOf(
            location {
                altitude = 2240.0
                speed = 3.4f
                bearing = 275.0f
            },
            null,
        )

        assertEquals(2240.0, fix.altitude!!, 1e-9)
        assertEquals(3.4f, fix.speed!!, 1e-6f)
        assertEquals(275.0f, fix.heading!!, 1e-6f)
    }

    @Test
    fun `a location becomes the documented payload`() {
        val encoded = WireJson.encodeToString(
            fixOf(
                location {
                    altitude = 2240.0
                    speed = 3.4f
                    bearing = 275.0f
                },
                batteryLevel = 87,
            ),
        )

        assertEquals(
            """{"latitude":19.4326,"longitude":-99.1332,"timestamp":1751000000000,""" +
                """"accuracy":8.0,"altitude":2240.0,"speed":3.4,"heading":275.0,""" +
                """"battery_level":87}""",
            encoded,
        )
    }
}

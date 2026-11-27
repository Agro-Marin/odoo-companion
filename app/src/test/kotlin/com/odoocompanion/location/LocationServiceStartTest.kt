package com.odoocompanion.location

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.odoocompanion.config.MAX_LOCATION_INTERVAL_SECONDS
import com.odoocompanion.config.MIN_LOCATION_INTERVAL_SECONDS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LocationServiceStartTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun nextStartedService() = Shadows.shadowOf(app).nextStartedService

    private fun drainStartedServices() {
        while (nextStartedService() != null) continue
    }

    @Test
    fun `no location grant means the service is not started at all`() {
        Shadows.shadowOf(app).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        LocationForegroundService.start(app)

        assertNull("started without a grant", nextStartedService())
    }

    @Test
    fun `either location grant is enough to start`() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)

        assertTrue(LocationForegroundService.canRun(app))
        LocationForegroundService.start(app)

        assertEquals(
            LocationForegroundService::class.java.name,
            nextStartedService()?.component?.className,
        )
        drainStartedServices()
    }

    @Test
    fun `canRun is false with neither grant`() {
        Shadows.shadowOf(app).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        assertFalse(LocationForegroundService.canRun(app))
    }

    @Test
    fun `stopping does not depend on the permission that starting does`() {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        LocationForegroundService.stop(app)

        assertEquals(
            LocationForegroundService::class.java.name,
            Shadows.shadowOf(app).nextStoppedService?.component?.className,
        )
    }

    @Test
    fun `the configured interval is the interval, not twice the rate`() {
        val request = LocationForegroundService.requestFor(60)

        assertEquals(60_000L, request.intervalMillis)
        assertEquals(
            "app_restrictions calls this \"seconds between position reports\"; " +
                "a shorter minimum lets the fused provider report twice as often, " +
                "which doubles the fleet's fixes, uploads and radio wakes",
            60_000L,
            request.minUpdateIntervalMillis,
        )
    }

    @Test
    fun `the floor and the ceiling of the managed interval both survive the request`() {
        assertEquals(
            MIN_LOCATION_INTERVAL_SECONDS * 1_000,
            LocationForegroundService.requestFor(MIN_LOCATION_INTERVAL_SECONDS).intervalMillis,
        )
        assertEquals(
            MAX_LOCATION_INTERVAL_SECONDS * 1_000,
            LocationForegroundService.requestFor(MAX_LOCATION_INTERVAL_SECONDS).intervalMillis,
        )
    }
}

package com.odoocompanion.system

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.odoocompanion.CompanionApp
import com.odoocompanion.config.enrol
import com.odoocompanion.location.LocationForegroundService
import com.odoocompanion.ui.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ReportingPausedTest {
    private val app: CompanionApp get() = ApplicationProvider.getApplicationContext()

    private val notices
        get() = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications

    @Before
    fun noticeNothingYet() {
        ReportingPaused.consume(app, null)
    }

    @Test
    fun `a refused start is announced, and opening the app from it starts reporting again`() =
        runBlocking<Unit> {
            shadowOf(app).grantPermissions(
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
            app.startup.join()
            app.config.enrol("https://odoo.example.com", "phone-01", "token")

            ReportingPaused.notice(app)
            val tap = shadowOf(notices.single().contentIntent).savedIntent
            assertEquals(ReportingPaused.ACTION_RESUME, tap.action)
            while (shadowOf(app).nextStartedService != null) Unit

            val screen = Robolectric.buildActivity(MainActivity::class.java, tap).setup()
            val service = LocationForegroundService::class.java.name
            val started = withTimeoutOrNull(10_000) {
                var found = false
                while (!found) {
                    shadowOf(Looper.getMainLooper()).idle()
                    val next = shadowOf(app).nextStartedService
                    found = next?.component?.className == service
                    if (next == null) delay(20)
                }
            }

            assertNotNull("opening the app from the notice started reporting again", started)
            assertTrue("and the notice is withdrawn", notices.isEmpty())
            screen.destroy()
        }

    @Test
    fun `an ordinary open has nothing to resume`() {
        assertFalse(ReportingPaused.consume(app, Intent(Intent.ACTION_MAIN)))
    }
}

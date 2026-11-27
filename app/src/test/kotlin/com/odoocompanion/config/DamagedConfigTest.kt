package com.odoocompanion.config

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DamagedConfigTest {
    @Test
    fun `a damaged configuration file leaves a device that can be enrolled again`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = File(app.filesDir, "datastore/companion_config.preferences_pb")
        store.parentFile?.mkdirs()
        store.writeBytes(byteArrayOf(0x1f, 0x2e, 0x3d, 0x4c, 0x5b, 0x6a, 0x79, 0x00))

        val config = DeviceConfig(app)

        assertFalse(config.current().isEnrolled)

        config.enrol("https://odoo.example.com", "phone-01", "t".repeat(64))

        assertTrue(config.current().isEnrolled)
    }
}

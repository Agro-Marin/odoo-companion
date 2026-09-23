package com.odoocompanion.system

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DeviceHealthTest {
    private val application: Application get() = ApplicationProvider.getApplicationContext()

    private fun exemptFromBatteryOptimization(exempt: Boolean) {
        val power = application.getSystemService(Context.POWER_SERVICE) as PowerManager
        val shadow = Shadows.shadowOf(power) as ShadowPowerManager
        shadow.setIgnoringBatteryOptimizations(application.packageName, exempt)
    }

    @Test
    fun `a phone with nothing granted reports every blocker`() {
        exemptFromBatteryOptimization(false)

        val report = DeviceHealth.report(application)

        assertFalse(report.locationGranted)
        assertFalse(report.backgroundLocationGranted)
        assertFalse(report.callLogGranted)
        assertFalse(report.exemptFromBatteryOptimization)
        assertEquals(
            listOf(Blocker.LOCATION_MISSING, Blocker.BATTERY_OPTIMIZED),
            report.blockers(callLogWanted = false, recordingsWanted = false),
        )
    }

    @Test
    fun `a fully provisioned phone reports no blockers`() {
        Shadows.shadowOf(application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.READ_CALL_LOG,
        )
        exemptFromBatteryOptimization(true)

        val report = DeviceHealth.report(application)

        assertTrue(report.locationGranted)
        assertTrue(report.callLogGranted)
        assertTrue(report.blockers(callLogWanted = true, recordingsWanted = false).isEmpty())
    }

    @Test
    fun `foreground-only location is still a blocker`() {
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        exemptFromBatteryOptimization(true)

        val report = DeviceHealth.report(application)

        assertTrue(report.locationGranted)
        assertFalse(report.backgroundLocationGranted)
        assertEquals(
            listOf(Blocker.BACKGROUND_LOCATION_MISSING),
            report.blockers(callLogWanted = false, recordingsWanted = false),
        )
    }

    @Test
    fun `battery optimization alone is enough to stop reporting`() {
        Shadows.shadowOf(application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        exemptFromBatteryOptimization(false)

        assertEquals(
            listOf(Blocker.BATTERY_OPTIMIZED),
            DeviceHealth.report(application).blockers(
                callLogWanted = false,
                recordingsWanted = false,
            ),
        )
    }

    @Test
    fun `recordings without access to the folder is a blocker, and only then`() {
        val report = DeviceHealth.report(application)

        assertFalse(report.recordingStorageGranted)
        assertTrue(
            Blocker.RECORDING_STORAGE_MISSING in
                report.blockers(callLogWanted = false, recordingsWanted = true),
        )
        assertFalse(
            Blocker.RECORDING_STORAGE_MISSING in
                report.blockers(callLogWanted = false, recordingsWanted = false),
        )
    }

    @Test
    @Config(sdk = [29], application = Application::class)
    fun `on Android 10 the read permission alone is not access to the recordings folder`() {
        Shadows.shadowOf(application).grantPermissions(
            Manifest.permission.READ_EXTERNAL_STORAGE,
        )
        ShadowEnvironment.setIsExternalStorageLegacy(false)

        assertFalse(DeviceHealth.hasRecordingStorageAccess(application))

        ShadowEnvironment.setIsExternalStorageLegacy(true)

        assertFalse(
            "reading is half the job: an uploaded recording has to be deletable too",
            DeviceHealth.hasRecordingStorageAccess(application),
        )

        Shadows.shadowOf(application).grantPermissions(
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )

        assertTrue(DeviceHealth.hasRecordingStorageAccess(application))
    }

    @Test
    fun `call sync without its permission is a blocker, and only then`() {
        Shadows.shadowOf(application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        exemptFromBatteryOptimization(true)

        val report = DeviceHealth.report(application)

        assertEquals(
            listOf(Blocker.CALL_LOG_PERMISSION_MISSING),
            report.blockers(callLogWanted = true, recordingsWanted = false),
        )
        assertTrue(report.blockers(callLogWanted = false, recordingsWanted = false).isEmpty())
    }

    @Test
    fun `a collector that is off costs no prompt`() {
        val bare = DeviceHealth.wantedPermissions(
            callLogWanted = false,
            recordingsWanted = false,
            sdk = 29,
        )

        assertFalse(Manifest.permission.READ_CALL_LOG in bare)
        assertFalse(Manifest.permission.READ_EXTERNAL_STORAGE in bare)
        assertFalse(Manifest.permission.WRITE_EXTERNAL_STORAGE in bare)
    }

    @Test
    fun `recordings ask for the legacy storage pair only where it is the access`() {
        val android10 = DeviceHealth.wantedPermissions(false, recordingsWanted = true, sdk = 29)
        val android12 = DeviceHealth.wantedPermissions(false, recordingsWanted = true, sdk = 31)

        assertTrue(Manifest.permission.READ_EXTERNAL_STORAGE in android10)
        assertTrue(Manifest.permission.WRITE_EXTERNAL_STORAGE in android10)
        assertFalse(
            "from 11 the folder takes all-files access; a runtime prompt grants nothing",
            Manifest.permission.READ_EXTERNAL_STORAGE in android12,
        )
    }

    @Test
    fun `notifications are asked for where they are a runtime permission`() {
        assertFalse(
            Manifest.permission.POST_NOTIFICATIONS in
                DeviceHealth.wantedPermissions(true, false, sdk = 32),
        )
        assertTrue(
            Manifest.permission.POST_NOTIFICATIONS in
                DeviceHealth.wantedPermissions(true, false, sdk = 33),
        )
    }
}

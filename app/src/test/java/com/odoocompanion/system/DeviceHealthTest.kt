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
            report.blockers(
                callLogWanted = false,
                recordingsWanted = false,
                softphoneWanted = false,
                localNetworkWanted = false,
            ),
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
        assertTrue(
            report.blockers(
                callLogWanted = true,
                recordingsWanted = false,
                softphoneWanted = false,
                localNetworkWanted = false,
            ).isEmpty(),
        )
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
            report.blockers(
                callLogWanted = false,
                recordingsWanted = false,
                softphoneWanted = false,
                localNetworkWanted = false,
            ),
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
                softphoneWanted = false,
                localNetworkWanted = false,
            ),
        )
    }

    @Test
    fun `recordings without access to the folder is a blocker, and only then`() {
        val report = DeviceHealth.report(application)

        assertFalse(report.recordingStorageGranted)
        assertTrue(
            Blocker.RECORDING_STORAGE_MISSING in
                report.blockers(
                    callLogWanted = false,
                    recordingsWanted = true,
                    softphoneWanted = false,
                    localNetworkWanted = false,
                ),
        )
        assertFalse(
            Blocker.RECORDING_STORAGE_MISSING in
                report.blockers(
                    callLogWanted = false,
                    recordingsWanted = false,
                    softphoneWanted = false,
                    localNetworkWanted = false,
                ),
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
            report.blockers(
                callLogWanted = true,
                recordingsWanted = false,
                softphoneWanted = false,
                localNetworkWanted = false,
            ),
        )
        assertTrue(
            report.blockers(
                callLogWanted = false,
                recordingsWanted = false,
                softphoneWanted = false,
                localNetworkWanted = false,
            ).isEmpty(),
        )
    }

    @Test
    @Config(sdk = [29])
    fun `a collector that is off costs no prompt`() {
        val bare = DeviceHealth.wantedPermissions(
            callLogWanted = false,
            recordingsWanted = false,
            softphoneWanted = false,
            localNetworkWanted = false,
        )

        assertFalse(Manifest.permission.READ_CALL_LOG in bare)
        assertFalse(Manifest.permission.READ_EXTERNAL_STORAGE in bare)
        assertFalse(Manifest.permission.WRITE_EXTERNAL_STORAGE in bare)
    }

    @Test
    @Config(sdk = [29])
    fun `recordings request both legacy storage permissions on Android 10`() {
        val wanted = DeviceHealth.wantedPermissions(false, true, false, false)
        assertTrue(Manifest.permission.READ_EXTERNAL_STORAGE in wanted)
        assertTrue(Manifest.permission.WRITE_EXTERNAL_STORAGE in wanted)
    }

    @Test
    @Config(sdk = [30])
    fun `recordings use all-files access from Android 11`() {
        val wanted = DeviceHealth.wantedPermissions(false, true, false, false)
        assertFalse(Manifest.permission.READ_EXTERNAL_STORAGE in wanted)
        assertFalse(Manifest.permission.WRITE_EXTERNAL_STORAGE in wanted)
    }

    @Test
    @Config(sdk = [32])
    fun `Android 12L needs no runtime notification prompt`() {
        val wanted = DeviceHealth.wantedPermissions(true, false, false, false)
        assertFalse(Manifest.permission.POST_NOTIFICATIONS in wanted)
    }

    @Test
    @Config(sdk = [33])
    fun `Android 13 requests the notification permission`() {
        val wanted = DeviceHealth.wantedPermissions(true, false, false, false)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in wanted)
    }

    @Test
    fun `a company line without the microphone is a blocker, and only then`() {
        val report = DeviceHealth.report(application)

        assertFalse(report.microphoneGranted)
        assertTrue(
            Blocker.MICROPHONE_MISSING in
                report.blockers(
                    callLogWanted = false,
                    recordingsWanted = false,
                    true,
                    localNetworkWanted = false
                ),
        )
        assertFalse(
            Blocker.MICROPHONE_MISSING in
                report.blockers(
                    callLogWanted = false,
                    recordingsWanted = false,
                    false,
                    localNetworkWanted = false
                ),
        )

        Shadows.shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)

        assertFalse(
            Blocker.MICROPHONE_MISSING in
                DeviceHealth.report(
                    application
                ).blockers(false, false, softphoneWanted = true, localNetworkWanted = false),
        )
    }

    @Test
    fun `the microphone is asked for only when the company line is set up`() {
        assertTrue(
            Manifest.permission.RECORD_AUDIO in
                DeviceHealth.wantedPermissions(
                    false,
                    false,
                    softphoneWanted = true,
                    localNetworkWanted = false,
                ),
        )
        assertFalse(
            Manifest.permission.RECORD_AUDIO in
                DeviceHealth.wantedPermissions(
                    false,
                    false,
                    softphoneWanted = false,
                    localNetworkWanted = false,
                ),
        )
    }

    @Test
    @Config(sdk = [37])
    fun `local network access is asked for from Android 17, and only for a local server`() {
        val local = Manifest.permission.ACCESS_LOCAL_NETWORK

        assertTrue(local in DeviceHealth.wantedPermissions(false, false, false, true))
        assertFalse(
            "a cloud server costs no nearby-devices prompt",
            local in DeviceHealth.wantedPermissions(false, false, false, false),
        )
    }

    @Test
    @Config(sdk = [36])
    fun `Android 16 needs no local network permission`() {
        val wanted = DeviceHealth.wantedPermissions(false, false, false, true)
        assertFalse(Manifest.permission.ACCESS_LOCAL_NETWORK in wanted)
    }

    @Test
    @Config(sdk = [37])
    fun `on Android 17 a local server without the grant is a blocker until it is granted`() {
        assertTrue(
            Blocker.LOCAL_NETWORK_MISSING in
                DeviceHealth.report(application).blockers(false, false, false, true),
        )
        assertFalse(
            Blocker.LOCAL_NETWORK_MISSING in
                DeviceHealth.report(application).blockers(false, false, false, false),
        )

        Shadows.shadowOf(application).grantPermissions(Manifest.permission.ACCESS_LOCAL_NETWORK)

        assertFalse(
            Blocker.LOCAL_NETWORK_MISSING in
                DeviceHealth.report(application).blockers(false, false, false, true),
        )
    }

    @Test
    @Config(sdk = [36])
    fun `below Android 17 the access is never missing`() {
        assertTrue(DeviceHealth.report(application).localNetworkGranted)
    }
}

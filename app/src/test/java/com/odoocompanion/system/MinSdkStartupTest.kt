package com.odoocompanion.system

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class MinSdkStartupTest {
    @Test
    fun `the manifest carries the permission ContextCompat demands below API 33`() {
        val app = RuntimeEnvironment.getApplication()
        val name = "${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        val info = app.packageManager.getPackageInfo(
            app.packageName,
            PackageManager.GET_PERMISSIONS,
        )

        assertTrue(
            "the app must request it, or CompanionApp.onCreate throws on Android 10 to 12L",
            name in info.requestedPermissions.orEmpty(),
        )
        assertTrue(
            "nothing grants a permission no package declares",
            name in info.permissions.orEmpty().map { it.name },
        )
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            app.packageManager.checkPermission(name, app.packageName),
        )
    }

    @Test
    fun `the manifest opts into legacy storage, or Android 10 can never see a recording`() {
        val app = RuntimeEnvironment.getApplication()
        val info = app.packageManager.getApplicationInfo(app.packageName, 0)
        val privateFlags = ApplicationInfo::class.java.getField("privateFlags").getInt(info)

        assertTrue(
            "isExternalStorageLegacy() is false without it, and the harvest walks the SD card",
            privateFlags and PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE != 0,
        )
    }

    @Test
    fun `the manifest asks for write access on Android 10, or an upload stays for ever`() {
        val app = RuntimeEnvironment.getApplication()
        val info = app.packageManager.getPackageInfo(
            app.packageName,
            PackageManager.GET_PERMISSIONS,
        )

        assertTrue(
            "legacy storage deletes nothing without it, and the harvest deletes its uploads",
            "android.permission.WRITE_EXTERNAL_STORAGE" in info.requestedPermissions.orEmpty(),
        )
    }

    private companion object {
        const val PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE = 1 shl 29
    }
}

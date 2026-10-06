package com.odoocompanion.softphone

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SoftphoneForegroundTest {
    private val specialUse = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    private val phoneCall = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
    private val microphone = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

    @Test
    fun `waiting for a call takes no type a boot start is refused`() {
        assertEquals(listOf(specialUse), SoftphoneService.foregroundChoices(onTheLine = false))
    }

    @Test
    fun `a call takes the microphone, and goes on without it when the background cannot`() {
        assertEquals(
            listOf(specialUse or phoneCall or microphone, specialUse or phoneCall),
            SoftphoneService.foregroundChoices(onTheLine = true),
        )
    }

    @Test
    fun `every set keeps a type android 15 allows after a boot start`() {
        // the boot-start reason is kept for the service's life, and a set with
        // none of the allowed types is refused for it as the boot start would be
        val all = SoftphoneService.foregroundChoices(false) +
            SoftphoneService.foregroundChoices(true)

        assertTrue(all.all { it and specialUse != 0 })
    }

    @Test
    fun `the manifest declares every type the service asks for, and their permissions`() {
        val app = RuntimeEnvironment.getApplication()
        val service = app.packageManager.getServiceInfo(
            ComponentName(app, SoftphoneService::class.java),
            0,
        )
        val asked = (
            SoftphoneService.foregroundChoices(false) +
                SoftphoneService.foregroundChoices(true)
            ).reduce(Int::or)
        val requested = app.packageManager
            .getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()

        assertEquals(asked, service.foregroundServiceType and asked)
        assertTrue("android.permission.FOREGROUND_SERVICE_SPECIAL_USE" in requested)
        assertTrue("android.permission.FOREGROUND_SERVICE_PHONE_CALL" in requested)
        assertTrue("android.permission.FOREGROUND_SERVICE_MICROPHONE" in requested)
    }

    @Test
    fun `the SIP stack starts with data saver ignored, telecom routing, and rtcp-mux`() {
        val sections = mutableMapOf<String, MutableMap<String, String>>()
        var section = ""
        SoftphoneEngine.CONFIG.lines().map(String::trim).filter(String::isNotEmpty).forEach {
            if (it.startsWith("[")) {
                section = it.trim('[', ']')
            } else {
                val (key, value) = it.split("=", limit = 2)
                sections.getOrPut(section) { mutableMapOf() }[key] = value
            }
        }

        assertEquals("1", sections["net"]?.get("android_ignore_network_background_restriction"))
        assertEquals("1", sections["sound"]?.get("android_disable_audio_route_changes"))
        assertEquals("1", sections["rtp"]?.get("rtcp_mux"))
    }
}

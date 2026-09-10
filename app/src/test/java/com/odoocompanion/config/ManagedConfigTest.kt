package com.odoocompanion.config

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ManagedConfigTest {
    @Test
    fun `no restrictions means nothing is managed`() {
        assertTrue(ManagedConfig.fromBundle(null).isEmpty)
        assertTrue(ManagedConfig.fromBundle(Bundle()).isEmpty)
    }

    @Test
    fun `an mdm can push a full enrollment`() {
        val bundle = Bundle().apply {
            putString("base_url", "https://odoo.example.com")
            putString("identifier", "phone-01")
            putString("token", "a".repeat(64))
            putBoolean("call_log_enabled", true)
            putBoolean("recordings_enabled", true)
            putBoolean("wifi_only_uploads", true)
            putInt("location_interval_seconds", 120)
        }

        val values = ManagedConfig.fromBundle(bundle)

        assertEquals("https://odoo.example.com", values.baseUrl)
        assertEquals("phone-01", values.identifier)
        assertEquals("a".repeat(64), values.token)
        assertEquals(true, values.recordingsEnabled)
        assertEquals(120L, values.locationIntervalSeconds)
    }

    @Test
    fun `a switch an emm sent as text is honoured, not read as false`() {
        val bundle = Bundle().apply {
            putString("call_log_enabled", "true")
            putString("recordings_enabled", "TRUE")
            putString("wifi_only_uploads", " yes ")
        }

        val values = ManagedConfig.fromBundle(bundle)

        assertEquals(true, values.callLogEnabled)
        assertEquals(true, values.recordingsEnabled)
        assertEquals(true, values.wifiOnlyUploads)
    }

    @Test
    fun `a switch an emm sent as text can also say no`() {
        val bundle = Bundle().apply {
            putString("call_log_enabled", "false")
            putString("recordings_enabled", "0")
            putString("wifi_only_uploads", "off")
        }

        val values = ManagedConfig.fromBundle(bundle)

        assertEquals(false, values.callLogEnabled)
        assertEquals(false, values.recordingsEnabled)
        assertEquals(false, values.wifiOnlyUploads)
    }

    @Test
    fun `a switch an emm sent as a number is honoured`() {
        val bundle = Bundle().apply {
            putInt("call_log_enabled", 1)
            putInt("recordings_enabled", 0)
        }

        val values = ManagedConfig.fromBundle(bundle)

        assertEquals(true, values.callLogEnabled)
        assertEquals(false, values.recordingsEnabled)
    }

    @Test
    fun `an unparseable switch leaves the stored value alone`() {
        val bundle = Bundle().apply {
            putString("call_log_enabled", "")
            putString("recordings_enabled", "maybe")
        }

        val values = ManagedConfig.fromBundle(bundle)

        assertNull(values.callLogEnabled)
        assertNull(values.recordingsEnabled)
    }

    @Test
    fun `a blank string does not wipe an existing enrollment`() {
        val bundle = Bundle().apply {
            putString("base_url", "   ")
            putString("identifier", "")
            putString("token", "")
        }

        val values = ManagedConfig.fromBundle(bundle)

        assertNull(values.baseUrl)
        assertNull(values.identifier)
        assertNull(values.token)
    }

    @Test
    fun `values are trimmed because consoles paste trailing whitespace`() {
        val bundle = Bundle().apply { putString("identifier", "  phone-02\n") }

        assertEquals("phone-02", ManagedConfig.fromBundle(bundle).identifier)
    }

    @Test
    fun `an unset boolean stays null rather than defaulting to false`() {
        val bundle = Bundle().apply { putString("identifier", "phone-03") }

        val values = ManagedConfig.fromBundle(bundle)

        assertNull(values.callLogEnabled)
        assertNull(values.recordingsEnabled)
        assertNull(values.wifiOnlyUploads)
    }

    @Test
    fun `a policy whose every value is unusable is still a policy, not a withdrawal`() {
        val bundle = Bundle().apply { putString("base_url", "odoo.example.com") }

        val values = ManagedConfig.fromBundle(bundle)

        assertNull(values.baseUrl)
        assertTrue("the form stays locked while an MDM is speaking", !values.isEmpty)
    }

    @Test
    fun `a base url that cannot build a request is ignored like a blank one`() {
        val bundle = Bundle().apply {
            putString("base_url", "odoo.example.com")
            putString("identifier", "phone-04")
        }

        val values = ManagedConfig.fromBundle(bundle)

        assertNull(values.baseUrl)
        assertEquals("phone-04", values.identifier)
    }

    @Test
    fun `a nonsensical interval is ignored`() {
        val bundle = Bundle().apply { putInt("location_interval_seconds", 0) }

        assertNull(ManagedConfig.fromBundle(bundle).locationIntervalSeconds)
    }

    @Test
    fun `an interval an emm sent as text is honoured, not discarded`() {
        val bundle = Bundle().apply { putString("location_interval_seconds", " 300 ") }

        assertEquals(300L, ManagedConfig.fromBundle(bundle).locationIntervalSeconds)
    }

    @Test
    fun `an interval sent as a number is still honoured`() {
        val bundle = Bundle().apply { putInt("location_interval_seconds", 300) }

        assertEquals(300L, ManagedConfig.fromBundle(bundle).locationIntervalSeconds)
    }

    @Test
    fun `an interval below the floor is clamped rather than applied`() {
        val bundle = Bundle().apply { putInt("location_interval_seconds", 1) }

        assertEquals(
            MIN_LOCATION_INTERVAL_SECONDS,
            ManagedConfig.fromBundle(bundle).locationIntervalSeconds,
        )
    }

    @Test
    fun `an absurd interval is clamped to a day`() {
        val bundle = Bundle().apply { putInt("location_interval_seconds", 99_999_999) }

        assertEquals(
            MAX_LOCATION_INTERVAL_SECONDS,
            ManagedConfig.fromBundle(bundle).locationIntervalSeconds,
        )
    }

    @Test
    fun `text that is not a number leaves the interval alone`() {
        val bundle = Bundle().apply { putString("location_interval_seconds", "every minute") }

        assertNull(ManagedConfig.fromBundle(bundle).locationIntervalSeconds)
    }

    @Test
    fun `an upload window is read and clamped`() {
        assertEquals(
            300L,
            ManagedConfig.fromBundle(
                Bundle().apply { putInt("upload_window_seconds", 300) },
            ).uploadWindowSeconds,
        )
        assertEquals(
            MAX_UPLOAD_WINDOW_SECONDS,
            ManagedConfig.fromBundle(
                Bundle().apply { putInt("upload_window_seconds", 99_999) },
            ).uploadWindowSeconds,
        )
    }

    @Test
    fun `an upload window of zero is honoured, unlike an interval of zero`() {
        assertEquals(
            0L,
            ManagedConfig.fromBundle(
                Bundle().apply { putInt("upload_window_seconds", 0) },
            ).uploadWindowSeconds,
        )
        assertNull(
            ManagedConfig.fromBundle(
                Bundle().apply { putInt("location_interval_seconds", 0) },
            ).locationIntervalSeconds,
        )
    }

    @Test
    fun `a negative upload window is refused rather than clamped to zero`() {
        assertNull(
            ManagedConfig.fromBundle(
                Bundle().apply { putInt("upload_window_seconds", -30) },
            ).uploadWindowSeconds,
        )
    }

    @Test
    fun `an upload window an emm sent as text is honoured`() {
        assertEquals(
            240L,
            ManagedConfig.fromBundle(
                Bundle().apply { putString("upload_window_seconds", "240") },
            ).uploadWindowSeconds,
        )
    }

    @Test
    fun `an upload window alone makes the push non-empty`() {
        val values = ManagedConfig.fromBundle(
            Bundle().apply { putInt("upload_window_seconds", 300) },
        )

        assertTrue(!values.isEmpty)
    }

    @Test
    fun `no restrictions service is unknown, not a withdrawn policy`() {
        val blind = object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.RESTRICTIONS_SERVICE) null else super.getSystemService(name)
        }

        assertNull(ManagedConfig.read(blind))
    }

    @Test
    fun `a reachable service with nothing set is a withdrawn policy`() {
        val values = ManagedConfig.read(ApplicationProvider.getApplicationContext())

        assertNotNull(values)
        assertTrue(values!!.isEmpty)
    }
}

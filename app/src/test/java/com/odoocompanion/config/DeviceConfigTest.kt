package com.odoocompanion.config

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DeviceConfigTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun config(): DeviceConfig {
        val file = File.createTempFile("config-test", ".preferences_pb").apply { delete() }
        temporaryFiles += file
        return DeviceConfig(PreferenceDataStoreFactory.create { file })
    }

    private val temporaryFiles = mutableListOf<File>()

    @After
    fun tearDown() {
        temporaryFiles.forEach { it.delete() }
    }

    @Test
    fun `a base url that cannot build a request is refused`() = runTest {
        val config = config()

        assertEquals(
            EnrollmentResult.InvalidBaseUrl,
            config.saveEnrollment("odoo.example.com", "phone-01", "token"),
        )
        assertEquals("", config.current().baseUrl)
    }

    @Test
    fun `a build that cannot send cleartext refuses an http base url at enrolment`() = runTest {
        val file = File.createTempFile("companion", ".preferences_pb")
        temporaryFiles += file
        val config = DeviceConfig(
            PreferenceDataStoreFactory.create { file },
            cleartextPermitted = false,
        )

        assertEquals(
            EnrollmentResult.CleartextRefused,
            config.saveEnrollment("http://odoo.example.com", "phone-01", "token"),
        )
        assertEquals(
            EnrollmentResult.Saved,
            config.saveEnrollment("https://odoo.example.com", "phone-01", "token"),
        )
    }

    @Test
    fun `a usable base url is stored`() = runTest {
        val config = config()

        assertEquals(
            EnrollmentResult.Saved,
            config.saveEnrollment(" https://odoo.example.com ", "phone-01", "token"),
        )
        assertEquals("https://odoo.example.com", config.current().baseUrl)
    }

    @Test
    fun `a bad identifier is reported as an identifier problem, not a url one`() = runTest {
        val config = config()

        assertEquals(
            EnrollmentResult.InvalidIdentifier,
            config.saveEnrollment("https://odoo.example.com", "phone 01", "token"),
        )
        assertEquals("", config.current().identifier)
    }

    @Test
    fun `the bearer token is not printed by toString`() {
        val text = Settings(token = "a-real-looking-bearer-token").toString()

        assertFalse(text.contains("a-real-looking-bearer-token"))
    }

    @Test
    fun `withdrawing the mdm policy unlocks the form without unenrolling`() = runTest {
        val config = config()
        config.applyManaged(
            ManagedValues(
                baseUrl = "https://odoo.example.com",
                identifier = "phone-01",
                token = "token",
            ),
        )
        assertTrue(config.current().managed)

        config.applyManaged(ManagedValues())

        val settings = config.current()
        assertFalse(settings.managed)
        assertTrue(settings.isEnrolled)
        assertEquals("https://odoo.example.com", settings.baseUrl)
    }

    @Test
    fun `a successful upload is timestamped and clears the previous error`() = runTest {
        val config = config()
        config.recordUpload(at = 100L, delivered = false, error = "network unreachable")

        config.recordUpload(at = 500L, delivered = true, error = null)

        val settings = config.current()
        assertEquals(500L, settings.lastUploadAt)
        assertNull(settings.lastUploadError)
    }

    @Test
    fun `a failed upload records why without claiming a successful upload time`() = runTest {
        val config = config()
        config.recordUpload(at = 100L, delivered = true, error = null)

        config.recordUpload(at = 900L, delivered = false, error = "server 500")

        val settings = config.current()
        assertEquals(100L, settings.lastUploadAt)
        assertEquals("server 500", settings.lastUploadError)
    }

    @Test
    fun `a drain with nothing to send does not claim an upload`() = runTest {
        val config = config()
        config.recordUpload(at = 100L, delivered = true, error = null)

        config.recordUpload(at = 900L, delivered = false, error = null)

        val settings = config.current()
        assertEquals(100L, settings.lastUploadAt)
        assertEquals(900L, settings.lastAttemptAt)
        assertNull(settings.lastUploadError)
    }

    @Test
    fun `one build change is consumed once, however many callers race for it`() = runTest {
        val config = config()

        val consumed = (1..8).map { async { config.consumeBuildChange(42L) } }.awaitAll()

        assertEquals(
            "startup and a restrictions broadcast can both call this at once",
            1,
            consumed.count { it },
        )
    }

    @Test
    fun `the cap the server declared is remembered, and forgettable`() = runTest {
        val config = config()

        config.learnPayloadLimit(1024L * 1024)
        assertEquals(1024L * 1024, config.current().maxPayloadBytes)

        config.learnPayloadLimit(0)
        assertEquals(
            "a revival re-probes, so the cap has to be droppable",
            0L,
            config.current().maxPayloadBytes,
        )
    }

    @Test
    fun `the upload window is stored and clamped`() = runTest {
        val config = config()

        config.setUploadWindow(300)
        assertEquals(300L, config.current().uploadWindowSeconds)

        config.setUploadWindow(99_999)
        assertEquals(MAX_UPLOAD_WINDOW_SECONDS, config.current().uploadWindowSeconds)

        config.setUploadWindow(-10)
        assertEquals(0L, config.current().uploadWindowSeconds)
    }

    @Test
    fun `an upload window of zero survives being stored`() = runTest {
        val config = config()

        config.setUploadWindow(0)

        assertEquals(0L, config.current().uploadWindowSeconds)
    }

    @Test
    fun `an unset upload window is the documented default`() = runTest {
        assertEquals(DEFAULT_UPLOAD_WINDOW_SECONDS, config().current().uploadWindowSeconds)
    }

    @Test
    fun `an mdm push that moves something reports that it did`() = runTest {
        val config = config()

        assertTrue(config.applyManaged(ManagedValues(identifier = "phone-01")))
    }

    @Test
    fun `pushing the same values twice reports no change the second time`() = runTest {
        val config = config()
        val values = ManagedValues(
            baseUrl = "https://odoo.example.com",
            identifier = "phone-01",
            token = "t".repeat(64),
            callLogEnabled = true,
        )
        assertTrue(config.applyManaged(values))

        assertFalse(config.applyManaged(values))
    }

    @Test
    fun `an empty push over an empty configuration reports no change`() = runTest {
        val config = config()

        assertFalse(config.applyManaged(ManagedValues()))
    }

    @Test
    fun `withdrawing a policy is itself a change`() = runTest {
        val config = config()
        config.applyManaged(ManagedValues(identifier = "phone-01"))

        assertTrue(config.applyManaged(ManagedValues()))
        assertFalse(config.current().managed)
    }

    @Test
    fun `a managed interval is stored as given, having been clamped upstream`() = runTest {
        val config = config()

        config.applyManaged(ManagedValues(locationIntervalSeconds = 300))

        assertEquals(300L, config.current().locationIntervalSeconds)
    }
}

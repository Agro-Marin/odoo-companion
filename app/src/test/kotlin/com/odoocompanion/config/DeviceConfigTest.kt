package com.odoocompanion.config

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

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
            config.enrol("odoo.example.com", "phone-01", "token"),
        )
        assertEquals("", config.current().baseUrl)
    }

    @Test
    fun `a build that cannot send cleartext refuses an http base url at enrolment`() = runTest {
        val file = File.createTempFile("companion", ".preferences_pb")
        temporaryFiles += file
        val config = DeviceConfig(
            PreferenceDataStoreFactory.create { file },
            cleartextPermitted = { false },
        )

        assertEquals(
            EnrollmentResult.CleartextRefused,
            config.enrol("http://odoo.example.com", "phone-01", "token"),
        )
        assertEquals(
            EnrollmentResult.Saved,
            config.enrol("https://odoo.example.com", "phone-01", "token"),
        )
    }

    @Test
    fun `a build that cannot send cleartext ignores an http base url a policy pushes`() = runTest {
        val file = File.createTempFile("companion", ".preferences_pb").apply { delete() }
        temporaryFiles += file
        val config = DeviceConfig(
            PreferenceDataStoreFactory.create { file },
            cleartextPermitted = { false },
        )
        config.enrol("https://odoo.example.com", "phone-01", "token")

        config.applyManaged(
            ManagedValues(
                baseUrl = "http://odoo.example.com",
                policyPresent = true,
                presentKeys = setOf("base_url"),
            ),
        )

        val settings = config.current()
        assertEquals("https://odoo.example.com", settings.baseUrl)
        assertTrue("the key is still the policy's", "base_url" in settings.governedKeys)
    }

    @Test
    fun `a policy whose new server is refused does not send its new credentials to the old one`() =
        runTest {
            val file = File.createTempFile("companion", ".preferences_pb").apply { delete() }
            temporaryFiles += file
            val config = DeviceConfig(
                PreferenceDataStoreFactory.create { file },
                cleartextPermitted = { false },
            )
            config.enrol("https://odoo.example.com", "phone-01", "token")

            config.applyManaged(
                ManagedValues(
                    baseUrl = "http://new.example.com",
                    identifier = "phone-77",
                    token = "new-token",
                    policyPresent = true,
                    presentKeys = setOf("base_url", "identifier", "token"),
                ),
            )

            val settings = config.current()
            assertEquals("https://odoo.example.com", settings.baseUrl)
            assertEquals("phone-01", settings.identifier)
            assertEquals("token", settings.token)
        }

    @Test
    fun `a policy whose server is unusable keeps the working enrolment whole`() = runTest {
        val config = config()
        config.enrol("https://odoo.example.com", "phone-01", "token")
        val values = ManagedConfig.fromBundle(
            Bundle().apply {
                putString("base_url", "odoo.example.com")
                putString("identifier", "phone-77")
                putString("token", "new-token")
                putBoolean("wifi_only_uploads", true)
            },
        )

        config.applyManaged(values)

        val settings = config.current()
        assertEquals("phone-01", settings.identifier)
        assertEquals("token", settings.token)
        assertTrue("the rest of the policy still applies", settings.wifiOnlyUploads)
    }

    @Test
    fun `a policy that rotates only the token still applies it`() = runTest {
        val config = config()
        config.enrol("https://odoo.example.com", "phone-01", "token")

        config.applyManaged(
            ManagedConfig.fromBundle(Bundle().apply { putString("token", "rotated") }),
        )

        assertEquals("rotated", config.current().token)
    }

    @Test
    fun `a host the network security policy opens takes an http base url`() = runTest {
        val file = File.createTempFile("companion", ".preferences_pb").apply { delete() }
        temporaryFiles += file
        val config = DeviceConfig(
            PreferenceDataStoreFactory.create { file },
            cleartextPermitted = { host -> host == "odoo.lan" },
        )

        assertEquals(
            EnrollmentResult.Saved,
            config.enrol("http://odoo.lan:8069", "phone-01", "token"),
        )
        assertEquals(
            EnrollmentResult.CleartextRefused,
            config.enrol("http://odoo.example.com", "phone-01", "token"),
        )
        assertEquals("http://odoo.lan:8069", config.current().baseUrl)
    }

    @Test
    fun `a store the disk cannot read answers nothing rather than throwing`() = runTest {
        val unreadable = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("I/O error") }

            override suspend fun updateData(
                transform: suspend (t: Preferences) -> Preferences,
            ): Preferences = throw IOException("I/O error")
        }

        assertNull(DeviceConfig(unreadable).currentOrNull())
    }

    @Test
    fun `a held queue confirmation waits for an in-progress enrolment change`() = runTest {
        val leaving = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val file = File.createTempFile("moving", ".preferences_pb").apply { delete() }
        temporaryFiles += file
        val config = DeviceConfig(PreferenceDataStoreFactory.create { file }) {
            leaving.complete(Unit)
            finish.await()
        }
        val url = "https://odoo.example.com"
        config.enrol(url, "phone-b", "t")
        val destination = requireNotNull(config.current().enrollment)
        val move = async { config.enrol(url, "phone-c", "t") }
        leaving.await()
        val confirmation = async(start = CoroutineStart.UNDISPATCHED) {
            config.withCurrentEnrollment(destination) { error("The old dialog changed the queue") }
        }
        finish.complete(Unit)
        assertEquals(EnrollmentResult.Saved, move.await())
        assertNull(confirmation.await())
    }

    @Test
    fun `what was learned about one server does not carry to another`() = runTest {
        val config = config()
        config.enrol("https://one.example.com", "phone-01", "token")
        config.learnServerNamesItself(config.current().baseUrl)
        assertTrue(config.current().serverNamesItself)

        config.enrol("https://one.example.com", "phone-02", "token")
        assertTrue("same server, same knowledge", config.current().serverNamesItself)

        config.enrol("https://two.example.com", "phone-01", "token")
        assertFalse(config.current().serverNamesItself)
    }

    @Test
    fun `a base url spelled another way is the same server and the same enrolment`() = runTest {
        val config = config()
        config.enrol("https://odoo.example.com", "phone-01", "token")
        config.learnServerNamesItself(config.current().baseUrl)
        val before = config.current().enrollment

        config.enrol("HTTPS://Odoo.Example.com:443/", "phone-01", "token")

        assertTrue("nothing about the server changed", config.current().serverNamesItself)
        assertEquals("so its queue is not held", before, config.current().enrollment)
        assertNotEquals(
            before,
            Settings(
                baseUrl = "https://odoo.example.com/other",
                identifier = "phone-01"
            ).enrollment,
        )
    }

    @Test
    fun `a usable base url is stored`() = runTest {
        val config = config()

        assertEquals(
            EnrollmentResult.Saved,
            config.enrol(" https://odoo.example.com ", "phone-01", "token"),
        )
        assertEquals("https://odoo.example.com", config.current().baseUrl)
    }

    @Test
    fun `a bad identifier is reported as an identifier problem, not a url one`() = runTest {
        val config = config()

        assertEquals(
            EnrollmentResult.InvalidIdentifier,
            config.enrol("https://odoo.example.com", "phone 01", "token"),
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
                policyPresent = true,
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
        config.recordUpload(
            config.current(),
            at = 100L,
            delivered = false,
            error = "network unreachable"
        )

        config.recordUpload(config.current(), at = 500L, delivered = true, error = null)

        val settings = config.current()
        assertEquals(500L, settings.lastUploadAt)
        assertNull(settings.lastUploadError)
    }

    @Test
    fun `a failed upload records why without claiming a successful upload time`() = runTest {
        val config = config()
        config.recordUpload(config.current(), at = 100L, delivered = true, error = null)

        config.recordUpload(
            config.current(),
            at = 900L,
            delivered = false,
            error = "server 500"
        )

        val settings = config.current()
        assertEquals(100L, settings.lastUploadAt)
        assertEquals("server 500", settings.lastUploadError)
    }

    @Test
    fun `a drain that delivered is an upload even when another row failed in it`() = runTest {
        val config = config()
        config.recordUpload(config.current(), at = 100L, delivered = true, error = null)

        config.recordUpload(
            config.current(),
            at = 900L,
            delivered = true,
            error = "server 500"
        )

        val settings = config.current()
        assertEquals(900L, settings.lastUploadAt)
        assertEquals("server 500", settings.lastUploadError)
    }

    @Test
    fun `a refusal of a replaced token cannot overwrite the current upload status`() = runTest {
        val config = config()
        config.enrol("https://odoo.example", "phone", "first")
        val old = config.current()
        config.enrol("https://odoo.example", "phone", "second")
        config.recordUpload(config.current(), 40, delivered = true, error = null)
        config.recordUpload(old, 50, delivered = false, error = "auth rejected (401)")
        assertEquals(40, config.current().lastAttemptAt)
        assertNull(config.current().lastUploadError)
    }

    @Test
    fun `a late reply from a former enrollment cannot change the new enrollment`() = runTest {
        val config = config()
        config.enrol("https://old.example", "phone", "token")
        val old = config.current()
        config.recordUpload(old, 30, delivered = true, error = null)
        config.enrol("https://new.example", "phone", "token")
        config.learnPayloadLimit(config.current().enrollment, 4096, at = 42)

        config.learnServerNamesItself(old.baseUrl)
        config.learnPayloadLimit(old.enrollment, 0, at = 43)
        config.recordUpload(old, 44, delivered = false, error = "stale failure")

        assertFalse(config.current().serverNamesItself)
        assertEquals(4096L, config.current().maxPayloadBytes)
        assertEquals(0L, config.current().lastUploadAt)
        assertEquals(0L, config.current().lastAttemptAt)
        assertNull(config.current().lastUploadError)
    }

    @Test
    fun `a cap learned for one device does not carry to another`() = runTest {
        val config = config()
        config.enrol("https://one.example.com", "phone-01", "token")
        config.learnPayloadLimit(config.current().enrollment, 1024L * 1024, at = 5_000L)

        config.enrol("https://one.example.com", "phone-01", "rotated")
        assertEquals(
            "a rotated token is the same device",
            1024L * 1024,
            config.current().maxPayloadBytes
        )

        config.enrol("https://one.example.com", "phone-02", "rotated")
        assertEquals(0L, config.current().maxPayloadBytes)

        config.learnPayloadLimit(config.current().enrollment, 1024L * 1024, at = 5_000L)
        config.applyManaged(ManagedValues(identifier = "phone-03", policyPresent = true))
        assertEquals(0L, config.current().maxPayloadBytes)
    }

    @Test
    fun `a drain with nothing to send does not claim an upload`() = runTest {
        val config = config()
        config.recordUpload(config.current(), at = 100L, delivered = true, error = null)

        config.recordUpload(config.current(), at = 900L, delivered = false, error = null)

        val settings = config.current()
        assertEquals(100L, settings.lastUploadAt)
        assertEquals(900L, settings.lastAttemptAt)
        assertNull(settings.lastUploadError)
    }

    @Test
    fun `one build change is consumed once, however many callers race for it`() = runTest {
        val config = config()

        var recoveries = 0
        (1..8).map { async { config.onBuildChanged(42L) { recoveries++ } } }.awaitAll()

        assertEquals(1, recoveries)
    }

    @Test
    fun `a failed build recovery is retried on the same build`() = runTest {
        val config = config()
        val failure = runCatching {
            config.onBuildChanged(42L) { throw IOException("storage unavailable") }
        }
        assertTrue(failure.exceptionOrNull() is IOException)

        var recovered = false
        config.onBuildChanged(42L) { recovered = true }
        assertTrue(recovered)
    }

    @Test
    fun `the cap the server declared is remembered, and forgettable`() = runTest {
        val config = config()

        config.learnPayloadLimit(config.current().enrollment, 1024L * 1024, at = 5_000L)
        assertEquals(1024L * 1024, config.current().maxPayloadBytes)
        assertEquals(1024L * 1024, config.current().payloadLimit(now = 65_000L))
        assertEquals(
            "a cap learned a day ago is not trusted to still be the cap",
            0L,
            config.current().payloadLimit(now = 5_000L + PAYLOAD_LIMIT_TTL_MILLIS),
        )

        config.learnPayloadLimit(config.current().enrollment, 0, at = 6_000L)
        assertEquals(
            "a revival re-probes, so the cap has to be droppable",
            0L,
            config.current().maxPayloadBytes,
        )
    }

    @Test
    fun `the upload window is stored and clamped`() = runTest {
        val config = config()

        config.saveForm(form(uploadWindowSeconds = 300))
        assertEquals(300L, config.current().uploadWindowSeconds)

        config.saveForm(form(uploadWindowSeconds = 99_999))
        assertEquals(MAX_UPLOAD_WINDOW_SECONDS, config.current().uploadWindowSeconds)

        config.saveForm(form(uploadWindowSeconds = -10))
        assertEquals(0L, config.current().uploadWindowSeconds)
    }

    @Test
    fun `an upload window of zero survives being stored`() = runTest {
        val config = config()

        config.saveForm(form(uploadWindowSeconds = 0))

        assertEquals(0L, config.current().uploadWindowSeconds)
    }

    @Test
    fun `an unset upload window is the documented default`() = runTest {
        assertEquals(DEFAULT_UPLOAD_WINDOW_SECONDS, config().current().uploadWindowSeconds)
    }

    @Test
    fun `an mdm push that moves something reports that it did`() = runTest {
        val config = config()

        assertTrue(
            config.applyManaged(ManagedValues(identifier = "phone-01", policyPresent = true))
        )
    }

    @Test
    fun `pushing the same values twice reports no change the second time`() = runTest {
        val config = config()
        val values = ManagedValues(
            baseUrl = "https://odoo.example.com",
            identifier = "phone-01",
            token = "t".repeat(64),
            callLogEnabled = true,
            policyPresent = true,
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
        config.applyManaged(ManagedValues(identifier = "phone-01", policyPresent = true))

        assertTrue(config.applyManaged(ManagedValues()))
        assertFalse(config.current().managed)
    }

    @Test
    fun `a managed interval is stored as given, having been clamped upstream`() = runTest {
        val config = config()

        config.applyManaged(ManagedValues(locationIntervalSeconds = 300, policyPresent = true))

        assertEquals(300L, config.current().locationIntervalSeconds)
    }
}

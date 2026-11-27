package com.odoocompanion.update

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.odoocompanion.net.WireJson
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowPackageInstaller
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UpdaterTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val manifest = UpdateManifest(99, "test", "test.apk", "0".repeat(64), 1)

    @Before
    fun setUp() {
        ShadowLog.setLoggable("Updater", Log.DEBUG)
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
    }

    private fun updater(
        builder: OkHttpClient.Builder = OkHttpClient.Builder(),
        body: (Call) -> ByteArray,
    ): Updater {
        val client = builder.addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body(chain.call()).toResponseBody()).build()
        }.build()
        return Updater(context, client)
    }

    @Test
    fun `two activities share one download and a failure releases the next attempt`() =
        runBlocking {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val requests = AtomicInteger()
            val downloader = updater {
                requests.incrementAndGet()
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                byteArrayOf(1, 2)
            }

            val first = async(Dispatchers.IO) { downloader.install(manifest) }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertEquals(
                    InstallStart.AlreadyInProgress,
                    updater {
                        error("second download")
                    }.install(manifest)
                )
            } finally {
                release.countDown()
            }
            assertTrue(first.await() is InstallStart.Failed)
            assertEquals(1, requests.get())
            assertNull(downloader.progress.value)
            assertTrue(File(context.cacheDir, "updates").listFiles()!!.isEmpty())
            assertTrue(downloader.install(manifest) is InstallStart.Failed)
            assertEquals(2, requests.get())
        }

    private val valid = manifest.copy(
        sha256 = MessageDigest.getInstance("SHA-256").digest(byteArrayOf(1))
            .joinToString("") { "%02x".format(it) },
    )

    @Test
    fun `only the current installer session can finish the update`() = runBlocking {
        val update = updater { byteArrayOf(1) }
        assertEquals(InstallStart.Started, update.install(valid))
        val session = context.packageManager.packageInstaller.mySessions.single().sessionId
        fun reply(id: Int, status: Int) {
            val intent = Intent().putExtra(PackageInstaller.EXTRA_SESSION_ID, id)
                .putExtra(PackageInstaller.EXTRA_STATUS, status)
            UpdateReceiver().onReceive(context, intent)
        }

        reply(session + 1, PackageInstaller.STATUS_FAILURE_ABORTED)
        assertEquals(UpdateStep.Installing, update.progress.value)
        assertEquals(InstallStart.AlreadyInProgress, update.install(valid))
        reply(session, PackageInstaller.STATUS_SUCCESS)
        assertNull(update.progress.value)
    }

    @Test
    fun `a restarted process respects a pending Android installer session`() = runBlocking {
        val installer = context.packageManager.packageInstaller
        val session = installer.createSession(
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL),
        )
        ReflectionHelpers.setField(installer.getSessionInfo(session), "sealed", true)
        val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
        prefs.edit().putInt("install_session", session).apply()
        val update = updater { error("another download before the pending session finishes") }
        assertEquals(InstallStart.AlreadyInProgress, update.install(valid))
        val reply = Intent().putExtra(PackageInstaller.EXTRA_SESSION_ID, session)
            .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
        UpdateReceiver().onReceive(context, reply)
        assertTrue(installer.mySessions.isEmpty())
        assertNull(update.progress.value)
    }

    @Test
    fun `a process stopped while staging leaves an unsealed session that can be retried`() =
        runBlocking {
            val installer = context.packageManager.packageInstaller
            val old = installer.createSession(
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL),
            )
            context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit()
                .putInt("install_session", old).apply()
            val update = updater { byteArrayOf(1) }
            assertEquals(InstallStart.Started, update.install(valid))
            val replacement = installer.mySessions.single().sessionId
            assertTrue(replacement != old)
            Updater.finish(context, replacement, installed = true)
        }

    @Test
    @Config(shadows = [FailingSession::class])
    fun `an installer write failure abandons the session and releases the download`() =
        runBlocking {
            val update = updater { byteArrayOf(1) }
            assertEquals(InstallStart.Failed("test fsync"), update.install(valid))
            assertTrue(context.packageManager.packageInstaller.mySessions.isEmpty())
            assertTrue(File(context.cacheDir, "updates").listFiles()!!.isEmpty())
            assertNull(update.progress.value)
        }

    @Implements(PackageInstaller.Session::class)
    class FailingSession : ShadowPackageInstaller.ShadowSession() {
        @Implementation
        override fun fsync(out: OutputStream) = throw IOException("test fsync")
    }

    @Test
    fun `a slow automatic check cannot overwrite the result of a later manual check`() =
        runBlocking(Dispatchers.IO) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val requests = AtomicInteger()
            val newer = manifest.copy(versionCode = Long.MAX_VALUE)
            val older = newer.copy(versionCode = Long.MAX_VALUE - 1)
            val network = Dispatcher()
            val update = updater(OkHttpClient.Builder().dispatcher(network)) {
                val result = if (requests.incrementAndGet() == 1) {
                    entered.countDown()
                    older
                } else {
                    newer
                }
                check(release.await(5, TimeUnit.SECONDS))
                WireJson.encodeToString(UpdateManifest.serializer(), result).toByteArray()
            }

            val auto = async(Dispatchers.IO) { update.check() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val manual = async(start = CoroutineStart.UNDISPATCHED) { update.check() }
            try {
                assertEquals(1, network.runningCallsCount() + network.queuedCallsCount())
            } finally {
                release.countDown()
            }
            assertEquals(UpdateCheck.Available(older), auto.await())
            assertEquals(UpdateCheck.Available(newer), manual.await())
            assertEquals(newer, update.known())
        }

    @Test
    fun `cancelled checks leave the next activity free to check again`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val published = manifest.copy(versionCode = Long.MAX_VALUE)
        val payload = WireJson.encodeToString(UpdateManifest.serializer(), published).toByteArray()
        lateinit var request: Call
        val update = updater { call ->
            request = call
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            payload
        }
        val check = async(Dispatchers.IO) { update.check() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            check.cancel()
            assertTrue(request.isCanceled())
        } finally {
            release.countDown()
        }
        check.join()
        assertNull(update.known())
        assertTrue(update.dueForCheck())
        assertEquals(UpdateCheck.Available(published), update.check())
        assertEquals(published, update.known())
    }

    @Test
    fun `an oversized feed is rejected before parsing any prefix`() = runBlocking {
        val update = updater { ByteArray(70_000) { 32 } }
        assertEquals(UpdateCheck.Failed("oversized update manifest"), update.check())
    }
}

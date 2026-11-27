package com.odoocompanion.softphone

import android.os.Looper
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.odoocompanion.CompanionApp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class SoftphoneUnreadableSettingsTest {
    private val app: CompanionApp get() = ApplicationProvider.getApplicationContext()

    private object Unreadable : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IOException("Inoperable file") }

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = throw IOException("Inoperable file")
    }

    @Test
    fun `settings the service cannot read leave the process running and a retry queued`() {
        runBlocking { app.startup.join() }
        ReflectionHelpers.setField(
            app,
            "softphones\$delegate",
            lazyOf(SoftphoneStore(Unreadable, app.config)),
        )

        val service = Robolectric.buildService(SoftphoneService::class.java).create()
            .startCommand(0, 1)
        repeat(100) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }

        val warned = ShadowLog.getLogsForTag("SoftphoneService")
            .any { it.type == Log.WARN && "Cannot read the softphone settings" in it.msg }
        assertTrue("the read failed and was handled", warned)
        assertTrue(shadowOf(service.get()).isStoppedBySelf)
        assertEquals(
            1,
            WorkManager.getInstance(app).getWorkInfosForUniqueWork("softphone-retry").get().size,
        )
    }
}

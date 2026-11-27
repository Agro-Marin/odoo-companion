package com.odoocompanion.ui

import android.content.ComponentName
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import com.odoocompanion.CompanionApp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareActivityTest {
    private val app: CompanionApp get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `leaving the share screen does not cancel the files still being imported`() {
        val info = app.packageManager.getActivityInfo(
            ComponentName(app, ShareActivity::class.java),
            0,
        )

        assertEquals(
            "noHistory finishes the screen when the user looks away, which cancels its import",
            0,
            info.flags and ActivityInfo.FLAG_NO_HISTORY,
        )
    }
}

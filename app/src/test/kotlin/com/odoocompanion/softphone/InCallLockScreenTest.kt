package com.odoocompanion.softphone

import android.app.Application
import android.app.KeyguardManager
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class InCallLockScreenTest {
    private var controller: ActivityController<InCallActivity>? = null

    @After
    fun tearDown() {
        SoftphoneCalls.book.clear()
        controller?.destroy()
        controller = null
    }

    private fun lock(locked: Boolean) {
        val keyguard = ApplicationProvider.getApplicationContext<Application>()
            .getSystemService(KeyguardManager::class.java)
        shadowOf(keyguard).setKeyguardLocked(locked)
    }

    private fun open(): InCallActivity {
        controller = Robolectric.buildActivity(InCallActivity::class.java).setup()
        return controller!!.get()
    }

    private fun callEnds(id: String) {
        SoftphoneCalls.book.ended(id)
        SoftphoneCalls.changed()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `the call controls sit above the navigation bar`() {
        lock(false)
        SoftphoneCalls.book.offer("call-1")
        val screen = open()
        val root: View = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val before = root.paddingBottom

        ViewCompat.dispatchApplyWindowInsets(
            root,
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, NAV_BAR))
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, STATUS_BAR, 0, 0))
                .build(),
        )

        assertEquals(before + NAV_BAR, root.paddingBottom)
        assertTrue(root.paddingTop >= STATUS_BAR)
    }

    @Test
    fun `a locked phone with no call does not open the dialer over the lock screen`() {
        lock(true)

        val screen = open()

        assertTrue(screen.isFinishing)
        assertFalse(shadowOf(screen).showWhenLocked)
    }

    @Test
    fun `a ringing call shows over the lock screen, and the screen closes when it ends`() {
        lock(true)
        SoftphoneCalls.book.offer("call-1")

        val screen = open()

        assertFalse(screen.isFinishing)
        assertTrue(shadowOf(screen).showWhenLocked)
        assertTrue(shadowOf(screen).turnScreenOn)

        callEnds("call-1")

        assertTrue("no dialer is left on a locked phone", screen.isFinishing)
    }

    @Test
    fun `an unlocked phone keeps the dialer after a call, without it reaching the lock screen`() {
        lock(false)
        SoftphoneCalls.book.offer("call-1")
        val screen = open()

        callEnds("call-1")

        assertFalse(screen.isFinishing)
        assertFalse(shadowOf(screen).showWhenLocked)
        assertFalse(shadowOf(screen).turnScreenOn)
    }

    private companion object {
        const val NAV_BAR = 132
        const val STATUS_BAR = 63
    }
}

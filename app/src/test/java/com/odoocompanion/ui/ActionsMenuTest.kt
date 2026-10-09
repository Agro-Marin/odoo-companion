package com.odoocompanion.ui

import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.odoocompanion.CompanionApp
import com.odoocompanion.R
import com.odoocompanion.config.ManagedValues
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
class ActionsMenuTest {
    private val app: CompanionApp get() = ApplicationProvider.getApplicationContext()
    private var controller: ActivityController<MainActivity>? = null

    @Before
    fun setUp() = runTest {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        app.startup.join()
        app.config.clearEnrollment()
        app.config.applyManaged(ManagedValues())
    }

    @After
    fun tearDown() {
        controller?.destroy()
        controller = null
    }

    private fun open(): MainActivity {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        return controller!!.get()
    }

    private fun MainActivity.menuShown() =
        findViewById<View>(R.id.actionsMenu).visibility == View.VISIBLE

    private fun MainActivity.tap(id: Int) {
        findViewById<View>(id).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `the gear opens the three actions and a tap outside closes them`() {
        val screen = open()
        assertFalse(screen.menuShown())

        screen.tap(R.id.actionsToggle)
        assertTrue(screen.menuShown())
        for (action in listOf(R.id.save, R.id.syncNow, R.id.grantPermissions)) {
            assertTrue(screen.findViewById<View>(action).isShown)
        }

        screen.tap(R.id.actionsScrim)
        assertFalse(screen.menuShown())
    }

    @Test
    fun `back closes the open menu before it leaves the screen`() {
        val screen = open()
        screen.tap(R.id.actionsToggle)

        screen.onBackPressedDispatcher.onBackPressed()

        assertFalse(screen.menuShown())
        assertFalse(screen.isFinishing)
    }

    @Test
    fun `an action closes the menu it was chosen from`() {
        val screen = open()
        screen.tap(R.id.actionsToggle)

        screen.tap(R.id.syncNow)

        assertFalse(screen.menuShown())
    }
}

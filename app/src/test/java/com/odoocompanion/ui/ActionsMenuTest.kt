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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `the gear opens the four actions and a tap outside closes them`() {
        val screen = open()
        assertFalse(screen.menuShown())

        screen.tap(R.id.actionsToggle)
        assertTrue(screen.menuShown())
        val actions =
            listOf(R.id.openSettings, R.id.syncNow, R.id.grantPermissions, R.id.checkUpdate)
        for (action in actions) {
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

    @Test
    fun `Settings opens the screen that holds the whole form`() {
        val screen = open()
        screen.tap(R.id.actionsToggle)

        screen.tap(R.id.openSettings)

        val started = Shadows.shadowOf(screen).nextStartedActivity
        assertEquals(SettingsActivity::class.java.name, started.component?.className)
        assertFalse(screen.menuShown())
    }

    // Home sends documents and nothing else: no field of the form is on it.
    @Test
    fun `home carries the two ways to send a document and none of the form`() {
        val screen = open()

        assertTrue(screen.findViewById<View>(R.id.scanDocument).isShown)
        assertTrue(screen.findViewById<View>(R.id.pickDocument).isShown)
        for (field in listOf(R.id.baseUrl, R.id.identifier, R.id.token, R.id.save)) {
            assertNull(screen.findViewById<View>(field))
        }
    }

    @Test
    fun `an unenrolled phone is pointed at Settings`() {
        val screen = open()

        assertTrue(screen.findViewById<View>(R.id.enrollPrompt).isShown)
    }
}

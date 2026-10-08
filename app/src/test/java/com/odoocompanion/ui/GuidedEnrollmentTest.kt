package com.odoocompanion.ui

import android.os.Looper
import android.view.View
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.odoocompanion.CompanionApp
import com.odoocompanion.R
import com.odoocompanion.config.ManagedValues
import com.odoocompanion.config.enrol
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
class GuidedEnrollmentTest {
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
        settle()
        return controller!!.get()
    }

    private fun settle() {
        repeat(30) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun MainActivity.shown(): List<Int> =
        listOf(R.id.guideBaseUrl, R.id.guideIdentifier, R.id.guideToken)
            .filter { findViewById<View>(it).visibility == View.VISIBLE }

    private fun MainActivity.type(id: Int, text: String) {
        findViewById<EditText>(id).setText(text)
        settle()
    }

    @Test
    fun `a fresh install points at the server first and walks on as fields fill`() {
        val screen = open()
        assertEquals(listOf(R.id.guideBaseUrl), screen.shown())

        screen.type(R.id.baseUrl, "https://odoo.example.com")
        assertEquals(listOf(R.id.guideIdentifier), screen.shown())

        screen.type(R.id.identifier, "phone-01")
        assertEquals(listOf(R.id.guideToken), screen.shown())

        screen.type(R.id.token, "t".repeat(64))
        assertEquals(emptyList<Int>(), screen.shown())
    }

    @Test
    fun `an enrolled phone shows no guide until a field is emptied`() = runTest {
        app.config.enrol("https://odoo.example.com", "phone-01", "token")
        val screen = open()
        assertEquals(emptyList<Int>(), screen.shown())

        screen.type(R.id.token, "")
        assertEquals(listOf(R.id.guideToken), screen.shown())
    }

    @Test
    fun `got it moves the guide to the next empty field`() {
        val screen = open()
        screen.findViewById<View>(R.id.guideBaseUrl)
            .findViewById<View>(R.id.guideDismiss)
            .performClick()
        settle()
        assertEquals(listOf(R.id.guideIdentifier), screen.shown())
    }

    @Test
    fun `the identifier field drops spaces as they are typed or pasted`() {
        val screen = open()
        val field = screen.findViewById<EditText>(R.id.identifier)
        field.setText("PHONE JEM 01")
        assertEquals("PHONEJEM01", field.text.toString())
        field.append(" lmmg-s26")
        assertEquals("PHONEJEM01lmmg-s26", field.text.toString())
    }
}

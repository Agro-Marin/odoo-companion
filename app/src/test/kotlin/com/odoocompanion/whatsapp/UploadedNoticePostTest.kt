package com.odoocompanion.whatsapp

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.odoocompanion.net.DocumentMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UploadedNoticePostTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private val notices
        get() = Shadows.shadowOf(app.getSystemService(NotificationManager::class.java))
            .allNotifications

    private val csf = QueuedDocument(
        DocumentMetadata(
            fileName = "CSF PCA221013LF1.pdf",
            mimetype = "application/pdf",
            kind = "csf",
            chat = "Yop",
            receivedAt = 1L,
        ),
        rfc = "PCA221013LF1",
    )

    private val ine = QueuedDocument(
        csf.metadata.copy(fileName = "INE.pdf", kind = "ine", receivedAt = 2L),
        rfc = null,
    )

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    @Test
    fun `a document that reached Odoo is announced with what it is and where it came from`() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        UploadedNotice.post(app, listOf(csf))

        val notice = notices.single()
        assertEquals("Constancia de Situación Fiscal uploaded to Documents", notice.title())
        assertEquals("RFC PCA221013LF1 · CSF PCA221013LF1.pdf · from Yop", notice.text())
    }

    @Test
    fun `several at once come grouped under one summary`() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        UploadedNotice.post(app, listOf(csf, ine))

        assertEquals(3, notices.size)
        assertTrue(notices.any { it.title() == "2 documents uploaded to Documents" })
    }

    @Test
    @Config(sdk = [29])
    fun `on Android 10 the notification setting controls document receipts`() {
        val manager = Shadows.shadowOf(app.getSystemService(NotificationManager::class.java))
        manager.setNotificationsEnabled(false)
        UploadedNotice.post(app, listOf(csf))
        assertTrue(notices.isEmpty())
        manager.setNotificationsEnabled(true)
        UploadedNotice.post(app, listOf(csf))
        assertEquals(1, notices.size)
    }

    @Test
    fun `without the notification permission nothing is posted`() {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        UploadedNotice.post(app, listOf(csf))

        assertTrue(notices.isEmpty())
    }
}

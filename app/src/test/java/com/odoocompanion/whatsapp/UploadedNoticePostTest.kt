package com.odoocompanion.whatsapp

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.odoocompanion.CompanionApp
import com.odoocompanion.net.DocumentMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class UploadedNoticePostTest {
    private val app: CompanionApp get() = ApplicationProvider.getApplicationContext()

    private val notices
        get() = Shadows.shadowOf(app.getSystemService(NotificationManager::class.java))
            .allNotifications

    private val csf = DocumentMetadata(
        fileName = "CSF PCA221013LF1.pdf",
        mimetype = "application/pdf",
        kind = "csf",
        rfc = "PCA221013LF1",
        chat = "Yop",
        receivedAt = 1L,
    )

    private val ine = csf.copy(fileName = "INE.pdf", kind = "ine", rfc = null, receivedAt = 2L)

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
    fun `without the notification permission nothing is posted`() {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        UploadedNotice.post(app, listOf(csf))

        assertTrue(notices.isEmpty())
    }
}

package com.odoocompanion.ui

import com.odoocompanion.scan.Scan
import com.odoocompanion.whatsapp.UploadState
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanPanelTest {
    private fun scan(path: String) = Scan(path, "$path.pdf", "Scanner", 1L)

    @Test
    fun `a scan still in the queue is queued, set aside is failed, gone is delivered`() {
        val queue = mapOf("/a" to false, "/b" to true)

        val states = ScanPanel.items(listOf(scan("/a"), scan("/b"), scan("/c")), queue)
            .map { it.state }

        assertEquals(
            listOf(UploadState.QUEUED, UploadState.SET_ASIDE, UploadState.DELIVERED),
            states,
        )
    }

    @Test
    fun `only the last few scans are shown`() {
        val scans = (1..ScanPanel.SHOWN + 3).map { scan("/$it") }

        assertEquals(ScanPanel.SHOWN, ScanPanel.items(scans, emptyMap()).size)
    }
}

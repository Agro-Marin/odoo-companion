package com.odoocompanion.ui

import com.odoocompanion.data.DeadReason
import com.odoocompanion.data.QueuedFileState
import com.odoocompanion.data.UploadState
import com.odoocompanion.scan.Scan
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanPanelTest {
    private fun scan(path: String) = Scan(path, "$path.pdf", "Scanner", 1L)

    @Test
    fun `a scan takes its queue status or a saved receipt`() {
        val queue = listOf(
            QueuedFileState("/a", null),
            QueuedFileState("/b", "refused"),
            QueuedFileState("/d", DeadReason.GONE),
            QueuedFileState("/e", DeadReason.WITHDRAWN),
        ).associateBy { it.filePath }

        val states = ScanPanel.items(
            listOf(scan("/a"), scan("/b"), scan("/c"), scan("/d"), scan("/e")),
            queue,
        ).map { it.state }

        assertEquals(
            listOf(
                UploadState.QUEUED,
                UploadState.SET_ASIDE,
                UploadState.UNCONFIRMED,
                UploadState.GONE,
                UploadState.WITHDRAWN,
            ),
            states,
        )
    }

    @Test
    fun `only the last few scans are shown`() {
        val scans = (1..ScanPanel.SHOWN + 3).map { scan("/$it") }

        assertEquals(ScanPanel.SHOWN, ScanPanel.items(scans, emptyMap()).size)
    }
}

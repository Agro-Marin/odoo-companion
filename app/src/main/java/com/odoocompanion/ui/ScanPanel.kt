package com.odoocompanion.ui

import com.odoocompanion.data.DeadReason
import com.odoocompanion.data.QueuedFileState
import com.odoocompanion.scan.Scan
import com.odoocompanion.whatsapp.UploadState

// What the Documents card lists: the last scans, each with where it stands.
// A scan whose file the outbox no longer holds was delivered -- the drain
// deletes the copy with the row -- so the queue alone answers, as it does for
// a WhatsApp document.
data class ScanItem(val scan: Scan, val state: UploadState)

object ScanPanel {
    const val SHOWN = 5

    fun items(scans: List<Scan>, queue: Map<String, QueuedFileState>): List<ScanItem> =
        scans.take(SHOWN).map { ScanItem(it, stateOf(it, queue)) }

    fun stateOf(scan: Scan, queue: Map<String, QueuedFileState>): UploadState {
        val row = queue[scan.filePath] ?: return UploadState.DELIVERED
        return when (row.deadReason) {
            null -> UploadState.QUEUED
            DeadReason.GONE -> UploadState.GONE
            DeadReason.WITHDRAWN -> UploadState.WITHDRAWN
            else -> UploadState.SET_ASIDE
        }
    }
}

package com.odoocompanion.ui

import com.odoocompanion.data.QueuedFileState
import com.odoocompanion.data.UploadState
import com.odoocompanion.scan.Scan

data class ScanItem(val scan: Scan, val state: UploadState)

object ScanPanel {
    const val SHOWN = 5

    fun items(scans: List<Scan>, queue: Map<String, QueuedFileState>): List<ScanItem> =
        scans.take(SHOWN).map { ScanItem(it, stateOf(it, queue)) }

    fun stateOf(scan: Scan, queue: Map<String, QueuedFileState>): UploadState =
        UploadState.forFile(queue[scan.filePath], scan.outcome)
}

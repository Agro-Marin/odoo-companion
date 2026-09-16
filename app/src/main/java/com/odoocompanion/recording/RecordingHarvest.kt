package com.odoocompanion.recording

import android.util.Log
import com.odoocompanion.data.OutboxDao
import com.odoocompanion.data.OutboxEntry
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.net.RecordingMetadata
import com.odoocompanion.net.WireJson
import kotlinx.serialization.encodeToString
import java.io.File

data class HarvestBatch(val queued: Int)

class RecordingHarvest(
    private val dao: OutboxDao,
    private val root: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun queueNew(): HarvestBatch {
        // Live and dead rows alike: a recording set aside, or one uploaded
        // whose file could not be removed, must not be queued a second time.
        val alreadyQueued = dao.filesQueued(OutboxKind.RECORDING).toHashSet()
        val fresh = mutableListOf<OutboxEntry>()
        for (scanned in RecordingScanner(root, now).scan()) {
            if (!alreadyQueued.add(scanned.file.absolutePath)) continue
            fresh += entryFor(scanned)
        }
        if (fresh.isNotEmpty()) {
            dao.insertAll(fresh)
            Log.i(TAG, "Queued ${fresh.size} recording(s)")
        }
        return HarvestBatch(fresh.size)
    }

    private fun entryFor(scanned: ScannedRecording): OutboxEntry {
        val file = scanned.file
        val parsed = RecordingFilename.of(file, scanned.modifiedAt)
        return OutboxEntry(
            kind = OutboxKind.RECORDING,
            payload = WireJson.encodeToString(
                RecordingMetadata(
                    number = parsed.number,
                    recordedAt = parsed.recordedAtMillis,
                    fileName = file.name,
                    mimetype = mimeTypeOf(file),
                ),
            ),
            filePath = file.absolutePath,
            createdAt = now(),
        )
    }

    companion object {
        private const val TAG = "RecordingHarvest"

        fun mimeTypeOf(file: File): String = when (file.extension.lowercase()) {
            "m4a", "mp4" -> "audio/mp4"
            "amr" -> "audio/amr"
            "mp3" -> "audio/mpeg"
            "3gp", "3gpp" -> "audio/3gpp"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            else -> "application/octet-stream"
        }
    }
}

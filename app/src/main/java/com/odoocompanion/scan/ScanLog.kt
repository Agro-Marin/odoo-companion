package com.odoocompanion.scan

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

// A document someone scanned or picked on the handset and handed to the outbox.
// Whether it reached Odoo is the outbox's to say: a delivered scan's row is
// gone, so this is the only memory of it the Documents card has.
@Serializable
data class Scan(val filePath: String, val fileName: String, val source: String, val at: Long,)

// The last few scans, newest first, kept in the app's private storage: a window
// onto what was sent from this handset, not a record of it.
class ScanLog(private val file: File) {
    private val lock = Mutex()

    private val state = MutableStateFlow<List<Scan>?>(null)

    val scans: StateFlow<List<Scan>?> = state.asStateFlow()

    suspend fun open() = lock.withLock {
        if (state.value == null) state.value = read()
    }

    suspend fun add(scan: Scan) = lock.withLock {
        val kept = (listOf(scan) + (state.value ?: read())).take(KEPT)
        write(kept)
        state.value = kept
    }

    private fun read(): List<Scan> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<List<Scan>>(file.readText())
        } catch (unreadable: SerializationException) {
            Log.w(TAG, "Starting a new scan list; the stored one does not decode", unreadable)
            emptyList()
        } catch (unreadable: IOException) {
            Log.w(TAG, "Starting a new scan list; the stored one cannot be read", unreadable)
            emptyList()
        }
    }

    // Written aside and renamed, so a process killed mid-write leaves the
    // previous list rather than half of one.
    private fun write(scans: List<Scan>) {
        file.parentFile?.mkdirs()
        val partial = File(file.path + ".tmp")
        partial.writeText(json.encodeToString(scans))
        if (!partial.renameTo(file)) {
            file.delete()
            partial.renameTo(file)
        }
    }

    companion object {
        const val KEPT = 10

        private const val TAG = "ScanLog"

        private val json = Json { ignoreUnknownKeys = true }
    }
}

package com.odoocompanion.scan

import android.util.Log
import com.odoocompanion.data.QueuedFile
import com.odoocompanion.data.UploadState
import com.odoocompanion.data.writeHistory
import com.odoocompanion.net.DocumentMetadata
import com.odoocompanion.net.WireJson
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
import java.nio.file.Files
import java.nio.file.NoSuchFileException

@Serializable
data class Scan(
    val filePath: String,
    val fileName: String,
    val source: String,
    val at: Long,
    val previewPath: String? = null,
    val outcome: UploadState? = null,
)

class ScanLog(private val file: File) {
    private val lock = Mutex()

    private val state = MutableStateFlow<List<Scan>?>(null)

    val scans: StateFlow<List<Scan>?> = state.asStateFlow()

    suspend fun open() = lock.withLock {
        if (state.value == null) {
            try {
                state.value = read()
            } catch (failure: IOException) {
                Log.w(TAG, "Scan history is unavailable (${failure.javaClass.simpleName})")
            }
        }
    }

    suspend fun add(scan: Scan, enqueue: suspend () -> Unit = {}) = lock.withLock {
        enqueue()
        save(listOf(scan) + (state.value ?: read()))
    }

    private fun save(all: List<Scan>) {
        val kept = all.take(KEPT)
        if (kept == state.value) return
        file.writeHistory(json.encodeToString(kept))
        state.value = kept
        all.drop(KEPT).mapNotNull { it.previewPath }.forEach { File(it).delete() }
    }

    suspend fun remember(files: List<QueuedFile>) = lock.withLock {
        val known = state.value ?: read().also { state.value = it }
        val receipts = files.associateBy { it.filePath }.toMutableMap()
        val updated = known.map { scan ->
            receipts.remove(scan.filePath)?.let { scan.copy(outcome = it.outcome) } ?: scan
        }
        val recovered = receipts.values.mapNotNull { row ->
            val payload = row.scanPayload ?: return@mapNotNull null
            val metadata = try {
                WireJson.decodeFromString<DocumentMetadata>(payload)
            } catch (_: SerializationException) {
                return@mapNotNull null
            }
            Scan(
                row.filePath,
                metadata.fileName,
                metadata.source.orEmpty(),
                row.createdAt,
                outcome = row.outcome
            )
        }
        if (recovered.isEmpty()) {
            save(updated)
        } else {
            save((updated + recovered).sortedByDescending { it.at })
        }
    }

    private fun read(): List<Scan> = try {
        Files.newBufferedReader(file.toPath()).use { json.decodeFromString(it.readText()) }
    } catch (_: NoSuchFileException) {
        emptyList()
    } catch (failure: SerializationException) {
        Log.w(TAG, "Stored scan history could not be decoded (${failure.javaClass.simpleName})")
        emptyList()
    }

    companion object {
        const val KEPT = 10

        private const val TAG = "ScanLog"

        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }
}

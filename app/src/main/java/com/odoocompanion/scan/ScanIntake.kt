package com.odoocompanion.scan

import android.util.Log
import com.odoocompanion.data.OutboxDao
import com.odoocompanion.data.OutboxEntry
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.data.OutboxLimits
import com.odoocompanion.net.DocumentMetadata
import com.odoocompanion.net.WireJson
import com.odoocompanion.system.debug
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

// A document chosen on the phone goes into the app's own storage first: the
// picker's grant on a file can lapse, and the scanner's output is a cache the
// system may clear before the upload runs. Then it is queued for the same
// route a WhatsApp document takes, and remembered for the Documents card.
class ScanIntake(
    private val folder: File,
    private val dao: OutboxDao,
    private val log: ScanLog,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Takes ownership of [input] and closes it before publishing the scan. */
    suspend fun receive(
        input: InputStream,
        fileName: String,
        mimetype: String,
        source: String,
        enrollment: String?,
        preview: (File, String) -> File? = { _, _ -> null },
    ): Scan {
        val at = now()
        val extension = extensionFor(mimetype, fileName)
        val file = File(folder, "${UUID.randomUUID()}.$extension")
        var picture: File? = null
        var queued = false
        try {
            input.use {
                folder.mkdirs()
                copy(it, file)
            }
            picture = preview(file, mimetype)
            val scan = Scan(file.absolutePath, fileName, source, at, picture?.absolutePath)
            currentCoroutineContext().ensureActive()
            // The committed row owns the copy even if the activity goes away during the insert.
            val saved = withContext(NonCancellable) {
                try {
                    log.add(scan) {
                        enqueue(scan, mimetype, enrollment)
                        queued = true
                    }
                    scan
                } catch (failure: IOException) {
                    if (!queued) throw failure
                    picture?.delete()
                    Log.w(TAG, "Queued document but could not save its history")
                    scan.copy(previewPath = null)
                }
            }
            debug(TAG) { "document queued, preview=${saved.previewPath != null}" }
            return saved
        } finally {
            if (!queued) {
                file.delete()
                picture?.delete()
            }
        }
    }

    private suspend fun copy(input: InputStream, file: File) {
        val coroutine = currentCoroutineContext()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var size = 0L
        file.outputStream().use { output ->
            while (true) {
                coroutine.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                if (size > OutboxLimits.MAX_FILE_BYTES) throw DocumentTooLarge()
                output.write(buffer, 0, count)
            }
            if (size == 0L) throw IOException("Empty document")
        }
    }

    private suspend fun enqueue(scan: Scan, mimetype: String, enrollment: String?) {
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.SCAN,
                payload = WireJson.encodeToString(
                    DocumentMetadata(
                        fileName = scan.fileName,
                        mimetype = mimetype,
                        source = scan.source,
                        receivedAt = scan.at,
                    ),
                ),
                filePath = scan.filePath,
                createdAt = scan.at,
                enrollment = enrollment,
            ),
        )
    }

    companion object {
        private const val TAG = "ScanIntake"

        const val FOLDER = "scans"

        // What the note on the document in Odoo says it came through.
        const val SOURCE_SCAN = "Scanner"
        const val SOURCE_FILE = "File"
        const val SOURCE_SHARE = "Shared"

        const val PDF = "application/pdf"

        /**
         * The extension the stored copy gets: the shared name's own when it has
         * one, else one read from [mimetype].
         */
        fun extensionFor(mimetype: String, fileName: String? = null): String {
            val named = fileName?.substringAfterLast('.', "")?.lowercase()
            if (!named.isNullOrEmpty() && named.length <= MAX_EXTENSION &&
                named.all { it.isLetterOrDigit() }
            ) {
                return named
            }
            return when (mimetype) {
                PDF -> "pdf"
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/heic", "image/heif" -> "heic"
                else -> if (mimetype.startsWith("image/")) "jpg" else "bin"
            }
        }

        private const val MAX_EXTENSION = 8
    }
}

internal class DocumentTooLarge : IOException()

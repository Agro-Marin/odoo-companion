package com.odoocompanion.scan

import com.odoocompanion.data.OutboxDao
import com.odoocompanion.data.OutboxEntry
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.net.DocumentMetadata
import com.odoocompanion.net.WireJson
import kotlinx.serialization.encodeToString
import java.io.File
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
    /** A copy of [input] in the app's own scan folder. */
    fun copy(input: InputStream, extension: String): File {
        val target = File(folder, "${UUID.randomUUID()}.$extension")
        folder.mkdirs()
        target.outputStream().use { input.copyTo(it) }
        return target
    }

    suspend fun queue(file: File, fileName: String, mimetype: String, source: String): Scan {
        val at = now()
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.SCAN,
                payload = WireJson.encodeToString(
                    DocumentMetadata(
                        fileName = fileName,
                        mimetype = mimetype,
                        source = source,
                        receivedAt = at,
                    ),
                ),
                filePath = file.absolutePath,
                createdAt = at,
            ),
        )
        return Scan(file.absolutePath, fileName, source, at).also { log.add(it) }
    }

    companion object {
        const val FOLDER = "scans"

        // What the note on the document in Odoo says it came through.
        const val SOURCE_SCAN = "Scanner"
        const val SOURCE_FILE = "File"
    }
}

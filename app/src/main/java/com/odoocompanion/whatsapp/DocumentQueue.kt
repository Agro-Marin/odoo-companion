package com.odoocompanion.whatsapp

import android.content.Context
import com.odoocompanion.CompanionApp
import com.odoocompanion.data.OutboxDao
import com.odoocompanion.data.OutboxEntry
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.data.QueuedFileState
import com.odoocompanion.data.UploadState
import com.odoocompanion.net.DocumentMetadata
import com.odoocompanion.sync.SyncScheduler
import com.odoocompanion.system.debug

class DocumentQueue(
    private val store: SightingStore,
    private val dao: OutboxDao,
    private val enrollment: String? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun queueClassified(): Int = store.enqueuePending { ready ->
        val inserted = dao.insertMissingDocuments(ready.map(::entryOf))
        debug(TAG) { "${ready.size} document(s) ready, $inserted queued" }
        inserted
    }

    private fun entryOf(sighting: Sighting): OutboxEntry {
        val path = requireNotNull(sighting.filePath)
        val read = requireNotNull(sighting.classification)
        return OutboxEntry(
            kind = OutboxKind.DOCUMENT,
            payload = QueuedDocument(
                metadata = DocumentMetadata(
                    fileName = path.substringAfterLast('/'),
                    mimetype = PDF,
                    kind = read.kind.name.lowercase(),
                    source = WhatsAppInbox.entries.firstOrNull { it.app == sighting.app }?.source
                        ?: WhatsAppInbox.PERSONAL.source,
                    chat = sighting.chat.ifBlank { null },
                    senderPhone = sighting.phone,
                    receivedAt = sighting.at,
                ),
                rfc = read.rfc,
            ).encode(),
            filePath = path,
            source = read.source,
            createdAt = now(),
            enrollment = enrollment,
        )
    }

    private companion object {
        const val TAG = "DocumentQueue"

        const val PDF = "application/pdf"
    }
}

object WhatsAppIntake {
    suspend fun process(context: Context) {
        val app = CompanionApp.from(context)
        app.whatsapp.classifyPending(app.documentReader::classify)
        val settings = app.config.currentOrNull() ?: return
        val queued = DocumentQueue(app.whatsapp, app.database.outbox(), settings.enrollment)
            .queueClassified()
        if (queued == 0) return
        if (settings.isEnrolled) SyncScheduler.uploadNow(context, settings.wifiOnlyUploads)
    }

    fun uploadStateOf(sighting: Sighting, queue: Map<String, List<QueuedFileState>>): UploadState? {
        if (sighting.classification?.kind?.uploads != true) return null
        val row = sighting.queueRow(queue)
        if (row == null && sighting.outcome == null && !sighting.queued) return UploadState.WAITING
        return sighting.uploadState(row)
    }
}

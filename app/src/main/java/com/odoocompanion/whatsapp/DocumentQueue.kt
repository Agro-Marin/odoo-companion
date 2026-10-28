package com.odoocompanion.whatsapp

import android.content.Context
import com.odoocompanion.CompanionApp
import com.odoocompanion.data.OutboxDao
import com.odoocompanion.data.OutboxEntry
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.net.DocumentMetadata
import com.odoocompanion.net.WireJson
import com.odoocompanion.sync.SyncScheduler
import com.odoocompanion.system.debug
import kotlinx.serialization.encodeToString

// Puts in the outbox each document the handset read as one that goes to Odoo.
// From there it is the outbox's: delivered, retried and set aside like a
// recording, except that its file is WhatsApp's and is never deleted.
class DocumentQueue(
    private val store: SightingStore,
    private val dao: OutboxDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun queueClassified(): Int {
        val ready = store.toUpload()
        if (ready.isEmpty()) return 0
        val queued = dao.filesQueued(OutboxKind.DOCUMENT).toHashSet()
        // A resend that WhatsApp saved no new file for is the same file: it
        // goes once, and the server answers any later copy as a duplicate.
        val fresh = ready.filter { sighting -> sighting.filePath?.let(queued::add) == true }
        if (fresh.isNotEmpty()) dao.insertAll(fresh.map(::entryOf))
        store.markQueued(ready.mapNotNullTo(HashSet()) { it.filePath })
        debug(TAG) { "${ready.size} document(s) ready, ${fresh.size} queued" }
        return fresh.size
    }

    private fun entryOf(sighting: Sighting): OutboxEntry {
        val read = sighting.classification
        return OutboxEntry(
            kind = OutboxKind.DOCUMENT,
            payload = WireJson.encodeToString(
                DocumentMetadata(
                    fileName = sighting.fileName ?: sighting.label,
                    mimetype = PDF,
                    kind = read?.kind?.name?.lowercase(),
                    rfc = read?.rfc,
                    source = if (sighting.app == WhatsAppNotices.WHATSAPP_BUSINESS) {
                        "WhatsApp Business"
                    } else {
                        "WhatsApp"
                    },
                    chat = sighting.chat.ifBlank { null },
                    senderPhone = sighting.phone,
                    receivedAt = sighting.at,
                ),
            ),
            filePath = sighting.filePath,
            createdAt = now(),
        )
    }

    private companion object {
        const val TAG = "DocumentQueue"

        // Only PDFs are read, so only PDFs are ever queued.
        const val PDF = "application/pdf"
    }
}

// The steps after a document is found, in one place for the listener and the
// screen: read what it is, queue what goes, and ask for an upload.
object WhatsAppIntake {
    private val reader = PageReader()

    suspend fun process(context: Context) {
        val app = CompanionApp.from(context)
        app.whatsapp.classifyPending(reader::classify)
        val queued = DocumentQueue(app.whatsapp, app.database.outbox()).queueClassified()
        if (queued == 0) return
        val settings = app.config.currentOrNull() ?: return
        if (settings.isEnrolled) SyncScheduler.uploadNow(context, settings.wifiOnlyUploads)
    }

    // A file the outbox no longer holds, of a document marked queued: it was
    // delivered. Read off the queue rather than recorded, since only the
    // drain knows, and it knows nothing of WhatsApp.
    fun uploadStateOf(sighting: Sighting, queue: Map<String, Boolean>): UploadState? {
        if (sighting.classification?.kind?.uploads != true) return null
        if (!sighting.queued) return UploadState.WAITING
        return when (queue[sighting.filePath]) {
            null -> UploadState.DELIVERED
            true -> UploadState.SET_ASIDE
            false -> UploadState.QUEUED
        }
    }
}

enum class UploadState { WAITING, QUEUED, DELIVERED, SET_ASIDE }

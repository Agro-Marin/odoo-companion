package com.odoocompanion.whatsapp

import android.content.Context
import com.odoocompanion.CompanionApp
import com.odoocompanion.data.DeadReason
import com.odoocompanion.data.OutboxDao
import com.odoocompanion.data.OutboxEntry
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.data.QueuedFileState
import com.odoocompanion.net.DocumentMetadata
import com.odoocompanion.net.WireJson
import com.odoocompanion.sync.SyncScheduler
import com.odoocompanion.system.debug
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

// Puts in the outbox each document the handset read as one that goes to Odoo.
// From there it is the outbox's: delivered, retried and set aside like a
// recording, except that its file is WhatsApp's and is never deleted.
class DocumentQueue(
    private val store: SightingStore,
    private val dao: OutboxDao,
    private val enrollment: String? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    // A notice, the folder watch, the poll and the screen each run the intake,
    // and two at once each found the file unqueued and queued it twice.
    suspend fun queueClassified(): Int = queueing.withLock {
        val ready = store.toUpload()
        if (ready.isEmpty()) return@withLock 0
        val queued = dao.filesQueued(OutboxKind.DOCUMENT).toHashSet()
        // A resend that WhatsApp saved no new file for is the same file: it
        // goes once, and the server answers any later copy as a duplicate.
        val fresh = ready.mapNotNull { sighting ->
            sighting.filePath?.takeIf(queued::add)?.let { path -> entryOf(sighting, path) }
        }
        if (fresh.isNotEmpty()) dao.insertAll(fresh)
        store.markQueued(ready.mapNotNullTo(HashSet()) { it.filePath })
        debug(TAG) { "${ready.size} document(s) ready, ${fresh.size} queued" }
        fresh.size
    }

    // The path exactly as the sighting holds it: the card finds the row by it.
    private fun entryOf(sighting: Sighting, path: String): OutboxEntry {
        val read = sighting.classification
        return OutboxEntry(
            kind = OutboxKind.DOCUMENT,
            payload = WireJson.encodeToString(
                DocumentMetadata(
                    fileName = path.substringAfterLast('/'),
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
            filePath = path,
            createdAt = now(),
            enrollment = enrollment,
        )
    }

    private companion object {
        const val TAG = "DocumentQueue"

        val queueing = Mutex()

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
        val settings = app.config.currentOrNull()
        val queued = DocumentQueue(app.whatsapp, app.database.outbox(), settings?.enrollment)
            .queueClassified()
        if (queued == 0 || settings == null) return
        if (settings.isEnrolled) SyncScheduler.uploadNow(context, settings.wifiOnlyUploads)
    }

    // A file the outbox no longer holds, of a document marked queued: it was
    // delivered. Read off the queue rather than recorded, since only the
    // drain knows, and it knows nothing of WhatsApp.
    fun uploadStateOf(sighting: Sighting, queue: Map<String, QueuedFileState>): UploadState? {
        if (sighting.classification?.kind?.uploads != true) return null
        if (!sighting.queued) return UploadState.WAITING
        val row = queue[sighting.filePath] ?: return UploadState.DELIVERED
        return when (row.deadReason) {
            null -> UploadState.QUEUED
            DeadReason.GONE -> UploadState.GONE
            DeadReason.WITHDRAWN -> UploadState.WITHDRAWN
            else -> UploadState.SET_ASIDE
        }
    }
}

enum class UploadState { WAITING, QUEUED, DELIVERED, SET_ASIDE, GONE, WITHDRAWN }

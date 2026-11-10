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

class DocumentQueue(
    private val store: SightingStore,
    private val dao: OutboxDao,
    private val enrollment: String? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun queueClassified(): Int = queueing.withLock {
        val ready = store.toUpload()
        if (ready.isEmpty()) return@withLock 0
        val queued = dao.filesQueued(OutboxKind.DOCUMENT).toHashSet()
        val fresh = ready.mapNotNull { sighting ->
            sighting.filePath?.takeIf(queued::add)?.let { path -> entryOf(sighting, path) }
        }
        if (fresh.isNotEmpty()) dao.insertAll(fresh)
        store.markQueued(ready.mapNotNullTo(HashSet()) { it.filePath })
        debug(TAG) { "${ready.size} document(s) ready, ${fresh.size} queued" }
        fresh.size
    }

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

        const val PDF = "application/pdf"
    }
}

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

package com.odoocompanion.whatsapp

import com.odoocompanion.net.DocumentMetadata
import com.odoocompanion.net.WireJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class QueuedDocument(val metadata: DocumentMetadata, val rfc: String?) {
    fun agreesWith(verdict: Classification): Boolean = verdict.kind.uploads &&
        (metadata.kind == null || metadata.kind == verdict.kind.name.lowercase()) &&
        (rfc == null || rfc == verdict.rfc)

    fun fromInbox(path: String, modifiedAt: Long): QueuedDocument {
        val inbox = WhatsAppInbox.containing(path) ?: return this
        if (metadata.source == inbox.source) return this
        return copy(
            metadata = metadata.copy(
                source = inbox.source,
                chat = null,
                senderPhone = null,
                receivedAt = modifiedAt
            )
        )
    }

    fun encode(): String {
        val sent = WireJson.encodeToJsonElement(metadata).jsonObject
        return JsonObject(if (rfc == null) sent else sent + (RFC to JsonPrimitive(rfc))).toString()
    }

    companion object {
        private const val RFC = "rfc"

        fun decode(payload: String): QueuedDocument {
            val stored = WireJson.parseToJsonElement(payload).jsonObject
            return QueuedDocument(
                metadata = WireJson.decodeFromJsonElement(stored),
                rfc = stored[RFC]?.jsonPrimitive?.contentOrNull,
            )
        }
    }
}

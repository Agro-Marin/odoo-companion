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

// A WhatsApp document waiting in the outbox: what Odoo is sent about it, and
// the tax identifier the handset read off it. The identifier is for the
// notice on this phone; it is kept beside the metadata, which never names it,
// so it does not travel.
data class QueuedDocument(val metadata: DocumentMetadata, val rfc: String?) {
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

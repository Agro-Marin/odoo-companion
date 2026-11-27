package com.odoocompanion.net

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun String.asJsonObject(): JsonObject? = try {
    Json.parseToJsonElement(this) as? JsonObject
} catch (_: SerializationException) {
    null
}

internal fun JsonObject?.primitive(key: String): JsonPrimitive? = this?.get(key) as? JsonPrimitive

internal fun JsonObject?.objectAt(key: String): JsonObject? = this?.get(key) as? JsonObject

package com.odoocompanion.net

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Reads of a reply by what each member should be. kotlinx's `jsonPrimitive` and
// `jsonObject` throw on any other shape, and that throw surfaced as the drain's
// last error -- "Element class JsonObject is not a JsonPrimitive" -- filed under
// a request that could not be built.
internal fun String.asJsonObject(): JsonObject? = try {
    Json.parseToJsonElement(this) as? JsonObject
} catch (_: SerializationException) {
    null
}

internal fun JsonObject?.primitive(key: String): JsonPrimitive? = this?.get(key) as? JsonPrimitive

internal fun JsonObject?.objectAt(key: String): JsonObject? = this?.get(key) as? JsonObject

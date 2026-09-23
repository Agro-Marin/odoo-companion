package com.odoocompanion.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val accuracy: Float? = null,
    val altitude: Double? = null,
    val speed: Float? = null,
    val heading: Float? = null,
    @SerialName("battery_level") val batteryLevel: Int? = null,
)

@Serializable
data class CallRecord(
    val number: String,
    val direction: String,
    val timestamp: Long,
    val duration: Long,
    @SerialName("contact_name") val contactName: String? = null,
)

@Serializable
data class RecordingMetadata(
    val number: String? = null,
    @SerialName("recorded_at") val recordedAt: Long,
    @SerialName("file_name") val fileName: String,
    val mimetype: String,
    val duration: Long? = null,
)

@Serializable
data class LocationUpload(val points: List<LocationFix>)

@Serializable
data class CallLogUpload(val calls: List<CallRecord>)

val WireJson: Json = Json {
    explicitNulls = false
    ignoreUnknownKeys = true
}

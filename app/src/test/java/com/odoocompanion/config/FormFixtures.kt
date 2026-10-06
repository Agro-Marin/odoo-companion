package com.odoocompanion.config

internal fun form(
    baseUrl: String = "https://odoo.example.com",
    identifier: String = "phone-01",
    token: String = "token",
    callLogEnabled: Boolean = true,
    recordingsEnabled: Boolean = false,
    wifiOnlyUploads: Boolean = false,
    locationIntervalSeconds: Long? = null,
    uploadWindowSeconds: Long? = null,
) = FormValues(
    baseUrl = baseUrl,
    identifier = identifier,
    token = token,
    callLogEnabled = callLogEnabled,
    recordingsEnabled = recordingsEnabled,
    wifiOnlyUploads = wifiOnlyUploads,
    locationIntervalSeconds = locationIntervalSeconds,
    uploadWindowSeconds = uploadWindowSeconds,
)

// Save with only the enrolment typed: every switch stays as stored.
internal suspend fun DeviceConfig.enrol(
    baseUrl: String,
    identifier: String,
    token: String,
): EnrollmentResult {
    val stored = current()
    return saveForm(
        form(
            baseUrl = baseUrl,
            identifier = identifier,
            token = token,
            callLogEnabled = stored.callLogEnabled,
            recordingsEnabled = stored.recordingsEnabled,
            wifiOnlyUploads = stored.wifiOnlyUploads,
        ),
    )
}

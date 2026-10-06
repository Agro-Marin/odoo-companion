package com.odoocompanion.config

// Each name is three things at once: the restriction key in
// app_restrictions.xml, the preference key the value is stored under, and the
// key the form locks by. DeviceConfig decides whether a field is governed by
// comparing its preference key's name with the policy's keys, which only works
// while the two are one string -- so they are spelled once, here.
object ManagedKey {
    const val BASE_URL = "base_url"
    const val IDENTIFIER = "identifier"
    const val TOKEN = "token"
    const val CALL_LOG_ENABLED = "call_log_enabled"
    const val RECORDINGS_ENABLED = "recordings_enabled"
    const val WIFI_ONLY_UPLOADS = "wifi_only_uploads"
    const val UPLOAD_WINDOW_SECONDS = "upload_window_seconds"
    const val LOCATION_INTERVAL_SECONDS = "location_interval_seconds"
    const val MIN_MOVE_METRES = "min_move_metres"

    val ALL = setOf(
        BASE_URL,
        IDENTIFIER,
        TOKEN,
        CALL_LOG_ENABLED,
        RECORDINGS_ENABLED,
        WIFI_ONLY_UPLOADS,
        UPLOAD_WINDOW_SECONDS,
        LOCATION_INTERVAL_SECONDS,
        MIN_MOVE_METRES,
    )
}

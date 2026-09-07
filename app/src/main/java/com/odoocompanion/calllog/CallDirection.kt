package com.odoocompanion.calllog

import android.provider.CallLog

internal object CallDirection {
    fun of(androidType: Int): String = KNOWN[androidType] ?: androidType.toString()

    private val KNOWN = mapOf(
        CallLog.Calls.INCOMING_TYPE to "incoming",
        CallLog.Calls.OUTGOING_TYPE to "outgoing",
        CallLog.Calls.MISSED_TYPE to "missed",
        CallLog.Calls.VOICEMAIL_TYPE to "voicemail",
        CallLog.Calls.REJECTED_TYPE to "rejected",
        CallLog.Calls.BLOCKED_TYPE to "blocked",

        CallLog.Calls.ANSWERED_EXTERNALLY_TYPE to "incoming",
    )
}

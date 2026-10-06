package com.odoocompanion.system

import android.util.Log

// Off unless asked for on the handset itself, per tag:
//   adb shell setprop log.tag.OutboxDrainer DEBUG
// so a release build carries the decisions a field diagnosis needs without
// writing them to every bug report. Counts, ids and codes only -- never a
// number, a contact or a recording path.
internal inline fun debug(tag: String, message: () -> String) {
    if (Log.isLoggable(tag, Log.DEBUG)) Log.d(tag, message())
}

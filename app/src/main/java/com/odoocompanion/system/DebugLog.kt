package com.odoocompanion.system

import android.util.Log

internal inline fun debug(tag: String, message: () -> String) {
    if (Log.isLoggable(tag, Log.DEBUG)) Log.d(tag, message())
}

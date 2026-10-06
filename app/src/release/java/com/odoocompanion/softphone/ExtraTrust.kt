package com.odoocompanion.softphone

import android.content.Context

/** Release builds trust the bundled roots only. */
object ExtraTrust {
    @Suppress("UNUSED_PARAMETER")
    fun rootCaData(context: Context, bundled: String?,): String? = null
}

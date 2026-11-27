package com.odoocompanion.softphone

import android.content.Context
import java.io.File

object ExtraTrust {
    fun rootCaData(context: Context, bundled: String?,): String? {
        val extra = File(context.filesDir, "extra-ca.pem").takeIf { it.isFile } ?: return null
        val roots = bundled?.let(::File)?.takeIf { it.isFile }?.readText().orEmpty()
        return roots + "\n" + extra.readText()
    }
}

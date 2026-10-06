package com.odoocompanion.softphone

import android.content.Context
import java.io.File

/**
 * A debug build also trusts the CA in `files/extra-ca.pem`: a lab phone system
 * signs its certificate with one the bundled roots do not hold. Release builds
 * trust the bundled roots only.
 */
object ExtraTrust {
    fun rootCaData(context: Context, bundled: String?,): String? {
        val extra = File(context.filesDir, "extra-ca.pem").takeIf { it.isFile } ?: return null
        val roots = bundled?.let(::File)?.takeIf { it.isFile }?.readText().orEmpty()
        return roots + "\n" + extra.readText()
    }
}

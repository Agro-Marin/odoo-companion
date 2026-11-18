package com.odoocompanion.scan

import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.odoocompanion.CompanionApp
import com.odoocompanion.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal enum class ImportResult { QUEUED, TOO_LARGE, UNREADABLE }

@Suppress("TooGenericExceptionCaught")
internal suspend fun CompanionApp.importDocument(
    uri: Uri,
    source: String,
    fileName: String? = null,
    fallbackType: String = "application/octet-stream",
): ImportResult = withContext(Dispatchers.IO) {
    try {
        val enrollment = config.current().enrollment
        val name = fileName ?: displayName(uri)
        val type = contentResolver.getType(uri) ?: fallbackType
        val intake = ScanIntake(File(filesDir, ScanIntake.FOLDER), database.outbox(), scans)
        val preview = ScanPreview(File(filesDir, ScanPreview.FOLDER))
        val input = contentResolver.openInputStream(uri) ?: throw IOException("No document stream")
        intake.receive(input, name, type, source, enrollment, preview::render)
        ImportResult.QUEUED
    } catch (stopped: CancellationException) {
        throw stopped
    } catch (_: DocumentTooLarge) {
        ImportResult.TOO_LARGE
    } catch (failure: Exception) {
        // Provider exception messages can contain the original URI or display name.
        Log.w("DocumentImport", "Could not queue a document (${failure.javaClass.simpleName})")
        ImportResult.UNREADABLE
    }
}

@Suppress("TooGenericExceptionCaught")
private fun CompanionApp.displayName(uri: Uri): String {
    val name = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (failure: RuntimeException) {
        Log.w("DocumentImport", "Could not read document name (${failure.javaClass.simpleName})")
        null
    }
    return name?.takeIf { it.isNotBlank() } ?: getString(R.string.scan_file_fallback)
}

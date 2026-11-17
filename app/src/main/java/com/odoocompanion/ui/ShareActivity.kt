package com.odoocompanion.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.odoocompanion.CompanionApp
import com.odoocompanion.R
import com.odoocompanion.scan.ScanIntake
import com.odoocompanion.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Companion in the system's share sheet: whatever photos or files are shared
// to it go to Odoo's Documents the way a picked file does. It has no screen of
// its own; it copies while it still holds the sender's read grant, says how it
// went, and closes.
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A restored instance has lost the copy it was running; the share
        // sheet's grant went with it.
        if (savedInstanceState != null) {
            finish()
            return
        }
        val uris = sharedUris(intent)
        if (uris.isEmpty()) {
            finish()
            return
        }
        lifecycleScope.launch {
            val queued = withContext(Dispatchers.IO) { uris.count { queue(it) } }
            if (queued > 0) {
                CompanionApp.from(this@ShareActivity).config.currentOrNull()
                    ?.takeIf { it.isEnrolled }
                    ?.let { SyncScheduler.uploadNow(this@ShareActivity, it.wifiOnlyUploads) }
            }
            Toast.makeText(this@ShareActivity, message(queued, uris.size), Toast.LENGTH_SHORT)
                .show()
            finish()
        }
    }

    private suspend fun queue(uri: Uri): Boolean = runCatching {
        val app = CompanionApp.from(this)
        val mimetype = contentResolver.getType(uri) ?: intent.type ?: ScanIntake.PDF
        val fileName = displayName(uri)
        val intake = ScanIntake(File(filesDir, ScanIntake.FOLDER), app.database.outbox(), app.scans)
        val input = requireNotNull(contentResolver.openInputStream(uri)) {
            "nothing to read at the shared item"
        }
        val file = input.use { intake.copy(it, ScanIntake.extensionFor(mimetype, fileName)) }
        val enrollment = app.config.currentOrNull()?.enrollment
        intake.queue(file, fileName, mimetype, ScanIntake.SOURCE_SHARE, enrollment)
    }.onFailure { Log.w(TAG, "Could not queue a shared item", it) }.isSuccess

    private fun message(queued: Int, shared: Int): String = when {
        queued == 0 -> getString(R.string.scan_unreadable)
        queued < shared -> getString(R.string.share_partly_queued, queued, shared)
        else -> resources.getQuantityString(R.plurals.share_queued, queued, queued)
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: getString(R.string.scan_file_fallback)

    private companion object {
        const val TAG = "ShareActivity"

        fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java),
            )

            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat
                    .getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    .orEmpty()

            else -> emptyList()
        }.distinct()
    }
}

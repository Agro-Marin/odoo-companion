package com.odoocompanion.ui

import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.odoocompanion.CompanionApp
import com.odoocompanion.R
import com.odoocompanion.scan.ImportResult
import com.odoocompanion.scan.ScanIntake
import com.odoocompanion.scan.importDocument
import com.odoocompanion.sync.SyncScheduler
import com.odoocompanion.system.debug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            val app = CompanionApp.from(this@ShareActivity)
            val results = withContext(Dispatchers.IO) {
                val grants = grantedUris(intent)
                uris.map { uri ->
                    if (canReadShare(uri, grants)) {
                        app.importDocument(
                            uri,
                            ScanIntake.SOURCE_SHARE,
                            fallbackType = intent.type ?: "application/octet-stream",
                        )
                    } else {
                        ImportResult.UNREADABLE
                    }
                }
            }
            val queued = results.count { it == ImportResult.QUEUED }
            if (queued > 0) {
                app.config.currentOrNull()
                    ?.takeIf { it.isEnrolled }
                    ?.let { SyncScheduler.uploadNow(this@ShareActivity, it.wifiOnlyUploads) }
            }
            Toast.makeText(this@ShareActivity, message(queued, results), Toast.LENGTH_SHORT)
                .show()
            finish()
        }
    }

    private fun canReadShare(uri: Uri, grants: Set<Uri>): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        // MediaStore ownership can be invisible to Android 15's caller snapshot.
        // An explicit read grant on this launch also authorizes the shared item.
        if (uri in grants && checkUriPermission(uri, Process.myPid(), Process.myUid(), read) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            debug("ShareActivity") { "using an explicit document read grant" }
            return true
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return false
        return try {
            initialCaller.checkContentUriPermission(uri, read) == PackageManager.PERMISSION_GRANTED
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun message(queued: Int, results: List<ImportResult>): String = when {
        results.all { it == ImportResult.TOO_LARGE } -> getString(R.string.scan_too_large)
        queued == 0 -> getString(R.string.scan_unreadable)
        queued < results.size -> getString(R.string.share_partly_queued, queued, results.size)
        else -> resources.getQuantityString(R.plurals.share_queued, queued, queued)
    }

    private companion object {
        fun grantedUris(intent: Intent): Set<Uri> {
            if (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return emptySet()
            val clip = intent.clipData ?: return emptySet()
            return (0 until clip.itemCount).mapNotNullTo(HashSet()) { clip.getItemAt(it).uri }
        }

        fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java),
            )

            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat
                    .getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    .orEmpty().filterIsInstance<Uri>()

            else -> emptyList()
        }.distinct()
    }
}

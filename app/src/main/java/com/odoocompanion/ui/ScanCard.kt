package com.odoocompanion.ui

import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.util.Log
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.odoocompanion.CompanionApp
import com.odoocompanion.R
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.databinding.ActivityMainBinding
import com.odoocompanion.scan.ScanIntake
import com.odoocompanion.sync.SyncScheduler
import com.odoocompanion.whatsapp.UploadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The Documents card: scan a page with the system's document scanner, or pick
// a PDF or a photo, and send it to Odoo's Documents as uploaded by whoever
// holds the phone. It is built in the activity's onCreate, since its result
// launchers have to be registered before the activity starts.
class ScanCard(private val activity: AppCompatActivity, private val binding: ActivityMainBinding,) {
    private val app = CompanionApp.from(activity)

    private val scanner = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val pdf = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pdf
        if (pdf != null) send(pdf.uri, scannedName(), PDF, ScanIntake.SOURCE_SCAN)
    }

    private val picker = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val mimetype = activity.contentResolver.getType(uri) ?: PDF
        send(uri, displayName(uri), mimetype, ScanIntake.SOURCE_FILE)
    }

    fun start() {
        binding.scanDocument.setOnClickListener { scan() }
        binding.pickDocument.setOnClickListener { picker.launch(PICKABLE) }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch(Dispatchers.IO) { app.scans.open() }
                combine(
                    app.scans.scans,
                    app.database.outbox().fileStates(OutboxKind.SCAN),
                ) { scans, queue ->
                    ScanPanel.items(scans.orEmpty(), queue.associateBy { it.filePath })
                }.collect(::show)
            }
        }
    }

    fun scan() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(PAGE_LIMIT)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
            .addOnSuccessListener { sender ->
                scanner.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Document scanner unavailable", error)
                toast(R.string.scan_unavailable)
            }
    }

    // Copies and queues off the main thread, then asks for an upload on the
    // network the phone is set to send on.
    private fun send(uri: Uri, fileName: String, mimetype: String, source: String) {
        activity.lifecycleScope.launch {
            val queued = runCatching {
                withContext(Dispatchers.IO) {
                    val input = requireNotNull(activity.contentResolver.openInputStream(uri)) {
                        "nothing to read at the chosen document"
                    }
                    val file = input.use { intake().copy(it, extensionOf(mimetype)) }
                    val enrollment = app.config.currentOrNull()?.enrollment
                    intake().queue(file, fileName, mimetype, source, enrollment)
                }
            }
            if (queued.isFailure) {
                Log.w(TAG, "Could not queue a document", queued.exceptionOrNull())
                toast(R.string.scan_unreadable)
                return@launch
            }
            app.config.currentOrNull()?.takeIf { it.isEnrolled }?.let { settings ->
                SyncScheduler.uploadNow(activity, settings.wifiOnlyUploads)
            }
            toast(R.string.scan_queued)
        }
    }

    private fun intake() = ScanIntake(
        File(activity.filesDir, ScanIntake.FOLDER),
        app.database.outbox(),
        app.scans,
    )

    private fun show(items: List<ScanItem>) {
        binding.scanState.text = activity.getString(
            if (items.isEmpty()) R.string.scan_none_yet else R.string.scan_recent,
        )
        binding.scanRecent.isVisible = items.isNotEmpty()
        binding.scanRecent.text = items.joinToString("\n\n") { item ->
            activity.getString(
                R.string.scan_item,
                item.scan.fileName,
                DateUtils.getRelativeTimeSpanString(
                    minOf(item.scan.at, System.currentTimeMillis()),
                ),
                activity.getString(
                    when (item.state) {
                        UploadState.QUEUED, UploadState.WAITING -> R.string.whatsapp_upload_queued
                        UploadState.DELIVERED -> R.string.whatsapp_upload_done
                        UploadState.SET_ASIDE -> R.string.whatsapp_upload_failed
                        UploadState.GONE -> R.string.whatsapp_upload_gone
                        UploadState.WITHDRAWN -> R.string.whatsapp_upload_withdrawn
                    },
                ),
            )
        }
    }

    // A fixed pattern: the locale's own ("3-11, 5:12 p.m.") ended in a dot
    // that met the extension's.
    private fun scannedName(): String = activity.getString(
        R.string.scan_file_name,
        SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.ROOT).format(Date()),
    )

    private fun displayName(uri: Uri): String = activity.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        ?.takeIf { it.isNotBlank() }
        ?: activity.getString(R.string.scan_file_fallback)

    private fun toast(@StringRes message: Int) =
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val TAG = "ScanCard"

        const val PDF = "application/pdf"

        const val PAGE_LIMIT = 20

        val PICKABLE = arrayOf(PDF, "image/*")

        fun extensionOf(mimetype: String): String = when (mimetype) {
            PDF -> "pdf"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/heic", "image/heif" -> "heic"
            else -> if (mimetype.startsWith("image/")) "jpg" else "bin"
        }
    }
}

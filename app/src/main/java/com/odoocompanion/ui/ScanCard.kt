package com.odoocompanion.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.DateUtils
import android.util.Log
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.odoocompanion.CompanionApp
import com.odoocompanion.R
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.databinding.ActivityMainBinding
import com.odoocompanion.databinding.DialogScanPreviewBinding
import com.odoocompanion.databinding.ItemScanBinding
import com.odoocompanion.scan.ImportResult
import com.odoocompanion.scan.ScanIntake
import com.odoocompanion.scan.ScanPreview
import com.odoocompanion.scan.importDocument
import com.odoocompanion.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScanCard(private val activity: AppCompatActivity, private val binding: ActivityMainBinding,) {
    private val app = CompanionApp.from(activity)

    private val scanner = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val pdf = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pdf
        if (pdf != null) send(pdf.uri, ScanIntake.SOURCE_SCAN, scannedName())
    }

    private val picker = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        send(uri, ScanIntake.SOURCE_FILE)
    }

    fun start() {
        binding.scanDocument.setOnClickListener { scan() }
        binding.pickDocument.setOnClickListener { picker.launch(PICKABLE) }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch(Dispatchers.IO) { app.scans.open() }
                combine(
                    app.scans.scans,
                    app.database.outbox().fileStates(OutboxKind.SCAN).distinctUntilChanged(),
                    statusMinutes,
                ) { scans, queue, _ ->
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

    private fun send(uri: Uri, source: String, fileName: String? = null) {
        activity.lifecycleScope.launch {
            when (app.importDocument(uri, source, fileName, fallbackType = PDF)) {
                ImportResult.UNREADABLE -> toast(R.string.scan_unreadable)

                ImportResult.TOO_LARGE -> toast(R.string.scan_too_large)

                ImportResult.QUEUED -> {
                    app.config.currentOrNull()?.takeIf { it.isEnrolled }?.let { settings ->
                        SyncScheduler.uploadNow(activity, settings.wifiOnlyUploads)
                    }
                    toast(R.string.scan_queued)
                }
            }
        }
    }

    private fun show(items: List<ScanItem>) {
        binding.scanState.text = activity.getString(
            if (items.isEmpty()) R.string.scan_none_yet else R.string.scan_recent,
        )
        binding.scanRecent.isVisible = items.isNotEmpty()
        binding.scanRecent.removeAllViews()
        val inflater = LayoutInflater.from(activity)
        items.forEach { item ->
            val row = ItemScanBinding.inflate(inflater, binding.scanRecent, true)
            row.scanName.text = item.scan.fileName
            row.scanMeta.text = meta(item)
            row.scanThumb.setImageResource(R.drawable.ic_file_placeholder)
            row.scanThumb.scaleType = ImageView.ScaleType.CENTER_INSIDE
            val preview = item.scan.previewPath?.let(::File)
            row.root.setOnClickListener { openPreview(item, preview) }
            if (preview != null) thumbnail(row.scanThumb, preview)
        }
    }

    private fun meta(item: ScanItem): String = activity.getString(
        R.string.scan_item_meta,
        DateUtils.getRelativeTimeSpanString(minOf(item.scan.at, System.currentTimeMillis())),
        activity.getString(item.state.labelResource),
    )

    private fun thumbnail(view: ImageView, preview: File) {
        thumbnails.get(preview.path)?.let { return show(view, it) }
        val size = view.layoutParams.width
        activity.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decode(preview, size) } ?: return@launch
            thumbnails.put(preview.path, bitmap)
            show(view, bitmap)
        }
    }

    private fun show(view: ImageView, bitmap: Bitmap) {
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        view.setImageBitmap(bitmap)
    }

    private fun openPreview(item: ScanItem, preview: File?) {
        if (preview == null || !preview.exists()) {
            toast(R.string.scan_no_preview)
            return
        }
        activity.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                decode(preview, ScanPreview.LONGEST_SIDE)
            } ?: return@launch toast(R.string.scan_no_preview)
            val dialog = DialogScanPreviewBinding.inflate(LayoutInflater.from(activity))
            dialog.previewName.text = item.scan.fileName
            dialog.previewMeta.text = meta(item)
            dialog.previewImage.setImageBitmap(bitmap)
            MaterialAlertDialogBuilder(activity)
                .setView(dialog.root)
                .setPositiveButton(R.string.whats_new_done, null)
                .show()
        }
    }

    private fun scannedName(): String = activity.getString(
        R.string.scan_file_name,
        SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.ROOT).format(Date()),
    )

    private fun toast(@StringRes message: Int) =
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    private val thumbnails = android.util.LruCache<String, Bitmap>(ScanPanel.SHOWN)

    private companion object {
        const val TAG = "ScanCard"

        fun decode(file: File, size: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return null
            var sample = 1
            while (longest / (sample * 2) >= size) sample *= 2
            return BitmapFactory.decodeFile(
                file.path,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }

        const val PDF = ScanIntake.PDF

        const val PAGE_LIMIT = 20

        val PICKABLE = arrayOf(PDF, "image/*")
    }
}

package com.odoocompanion.whatsapp

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.graphics.createBitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.odoocompanion.system.debug
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutionException
import kotlin.math.sqrt

// Reads what a PDF says it is, on the handset: its first pages are drawn and
// put through ML Kit's OCR and QR reader, which run on the device with no
// network. Drawing rather than extracting text is deliberate: an INE or a
// passport arrives as a scan, with no text layer to extract. Blocking, so it
// runs off the main thread.
class PageReader {
    // Made once and kept: building a recognizer loads its model, which was
    // paid for every file read.
    private val text by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val qr by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
        )
    }

    // Null when the file could not be read this time -- a damaged PDF, an OCR
    // failure -- so it is asked again later, up to Sighting.MAX_READ_ATTEMPTS,
    // instead of being ruled "other" and never sent.
    fun classify(file: File): Classification? {
        if (file.extension.lowercase() != "pdf") return Classification(DocumentKind.OTHER)
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use(::read)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not draw a document", e)
            null
        } catch (e: SecurityException) {
            // A password-protected PDF: nothing on it can be read.
            Log.w(TAG, "A document is locked", e)
            Classification(DocumentKind.OTHER)
        }
    }

    // One page drawn, read and freed at a time, and no further page once one
    // decides: both pages used to be held at once, and the second read for a
    // constancia whose title and QR are on its first.
    private fun read(pdf: PdfRenderer): Classification? {
        val texts = mutableListOf<String>()
        val codes = mutableListOf<String>()
        var verdict = Classification(DocumentKind.OTHER)
        var pages = 0
        while (pages < minOf(pdf.pageCount, MAX_PAGES) && !verdict.kind.uploads) {
            val bitmap = draw(pdf, pages++)
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                texts += Tasks.await(text.process(image)).text
                codes += Tasks.await(qr.process(image)).mapNotNull { it.rawValue }
            } catch (e: ExecutionException) {
                // The models are bundled, so this is the recognizer failing on
                // this page, not a download still under way.
                Log.w(TAG, "OCR failed; will try again", e.cause as? MlKitException ?: e)
                return null
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w(TAG, "OCR interrupted", e)
                return null
            } finally {
                bitmap.recycle()
            }
            verdict = DocumentClassifier.classify(texts.joinToString("\n"), codes)
        }
        debug(TAG) { "read $pages page(s), ${codes.size} QR: ${verdict.kind}" }
        return verdict
    }

    private fun draw(pdf: PdfRenderer, index: Int): Bitmap = pdf.openPage(index).use { page ->
        val (width, height) = bitmapSize(page.width, page.height)
        createBitmap(width, height).also { bitmap ->
            // A PDF page is transparent where nothing is drawn, and OCR reads
            // black-on-transparent as nothing at all.
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    internal companion object {
        private const val TAG = "PageReader"

        // A constancia's title and QR are on its first page; an INE sent as a
        // PDF is usually its front and back on the first two.
        private const val MAX_PAGES = 2

        // Wide enough for OCR to read an INE's machine-readable zone.
        const val TARGET_WIDTH = 1600

        // A legal-size page at TARGET_WIDTH, about 17 MB of ARGB.
        const val MAX_PIXELS = 4_500_000.0

        // The bitmap a page is drawn into: TARGET_WIDTH wide, unless the page is
        // so long that the width would cost more than MAX_PIXELS. Scaled by width
        // alone, a receipt-shaped page asked for gigabytes, and the process died
        // on it at every pass, since the file stayed unread.
        fun bitmapSize(pageWidth: Int, pageHeight: Int): Pair<Int, Int> {
            val width = pageWidth.coerceAtLeast(1).toDouble()
            val height = pageHeight.coerceAtLeast(1).toDouble()
            val scale = minOf(TARGET_WIDTH / width, sqrt(MAX_PIXELS / (width * height)))
            return (width * scale).toInt().coerceAtLeast(1) to
                (height * scale).toInt().coerceAtLeast(1)
        }
    }
}

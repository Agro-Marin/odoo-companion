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

// Reads what a PDF says it is, on the handset: its first pages are drawn and
// put through ML Kit's OCR and QR reader, which run on the device with no
// network. Drawing rather than extracting text is deliberate: an INE or a
// passport arrives as a scan, with no text layer to extract. Blocking, so it
// runs off the main thread.
class PageReader {
    // Null when the file could not be read this time -- not yet downloaded
    // model, a damaged or locked PDF -- so it is asked again later instead of
    // being ruled "other" and never sent.
    fun classify(file: File): Classification? {
        if (file.extension.lowercase() != "pdf") return Classification(DocumentKind.OTHER)
        val pages = try {
            render(file)
        } catch (e: IOException) {
            Log.w(TAG, "Could not draw a document", e)
            return null
        } catch (e: SecurityException) {
            // A password-protected PDF: nothing on it can be read.
            Log.w(TAG, "A document is locked", e)
            return Classification(DocumentKind.OTHER)
        }
        val text = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val qr = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
        )
        return try {
            val texts = mutableListOf<String>()
            val codes = mutableListOf<String>()
            for (page in pages) {
                val image = InputImage.fromBitmap(page, 0)
                texts += Tasks.await(text.process(image)).text
                codes += Tasks.await(qr.process(image)).mapNotNull { it.rawValue }
            }
            DocumentClassifier.classify(texts.joinToString("\n"), codes).also {
                debug(TAG) { "read ${pages.size} page(s), ${codes.size} QR: ${it.kind}" }
            }
        } catch (e: ExecutionException) {
            // MlKitException UNAVAILABLE while Play services fetches the model.
            Log.w(TAG, "OCR failed; will try again", e.cause as? MlKitException ?: e)
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "OCR interrupted", e)
            null
        } finally {
            text.close()
            qr.close()
            pages.forEach(Bitmap::recycle)
        }
    }

    private fun render(file: File): List<Bitmap> =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { pdf ->
                (0 until minOf(pdf.pageCount, MAX_PAGES)).map { index ->
                    pdf.openPage(index).use { page ->
                        val scale = TARGET_WIDTH.toFloat() / page.width
                        val bitmap = createBitmap(
                            TARGET_WIDTH,
                            (page.height * scale).toInt().coerceAtLeast(1),
                        )
                        // A PDF page is transparent where nothing is drawn, and
                        // OCR reads black-on-transparent as nothing at all.
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
        }

    private companion object {
        const val TAG = "PageReader"

        // A constancia's title and QR are on its first page; an INE sent as a
        // PDF is usually its front and back on the first two.
        const val MAX_PAGES = 2

        // Wide enough for OCR to read an INE's machine-readable zone, small
        // enough that two pages stay a few megabytes.
        const val TARGET_WIDTH = 1600
    }
}

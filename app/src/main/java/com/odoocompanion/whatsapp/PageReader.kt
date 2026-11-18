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
import java.io.FileDescriptor
import java.io.IOException
import java.util.concurrent.ExecutionException
import kotlin.math.sqrt

class PageReader {
    private val text by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val qr by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
        )
    }

    fun classify(descriptor: FileDescriptor): Classification? = try {
        ParcelFileDescriptor.dup(descriptor).use { fd ->
            PdfRenderer(fd).use(::read)
        }
    } catch (e: IOException) {
        Log.w(TAG, "Could not draw a document (${e.javaClass.simpleName})")
        null
    } catch (_: SecurityException) {
        Log.w(TAG, "A document is locked")
        Classification(DocumentKind.OTHER)
    }

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
                val code = (e.cause as? MlKitException)?.errorCode
                Log.w(TAG, "OCR failed (code $code), will retry")
                return null
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w(TAG, "OCR interrupted")
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
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    internal companion object {
        private const val TAG = "PageReader"

        private const val MAX_PAGES = 2

        const val TARGET_WIDTH = 1600

        const val MAX_PIXELS = 4_500_000.0

        fun bitmapSize(pageWidth: Int, pageHeight: Int): Pair<Int, Int> {
            val width = pageWidth.coerceAtLeast(1).toDouble()
            val height = pageHeight.coerceAtLeast(1).toDouble()
            val scale = minOf(TARGET_WIDTH / width, sqrt(MAX_PIXELS / (width * height)))
            return (width * scale).toInt().coerceAtLeast(1) to
                (height * scale).toInt().coerceAtLeast(1)
        }
    }
}

package com.odoocompanion.scan

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.graphics.createBitmap
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

class ScanPreview(private val folder: File) {
    fun render(source: File, mimetype: String): File? {
        return try {
            val bitmap =
                when {
                    mimetype == ScanIntake.PDF -> firstPage(source)
                    mimetype.startsWith("image/") -> photo(source)
                    else -> null
                } ?: return null
            try {
                save(bitmap)
            } finally {
                bitmap.recycle()
            }
        } catch (unreadable: IOException) {
            Log.w(TAG, "Could not make a document preview (${unreadable.javaClass.simpleName})")
            null
        } catch (unreadable: SecurityException) {
            Log.w(TAG, "Could not make a document preview (${unreadable.javaClass.simpleName})")
            null
        } catch (unreadable: IllegalArgumentException) {
            Log.w(TAG, "Could not make a document preview (${unreadable.javaClass.simpleName})")
            null
        }
    }

    private fun save(bitmap: Bitmap): File {
        folder.mkdirs()
        val target = File(folder, "${UUID.randomUUID()}.jpg")
        try {
            target.outputStream().use {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it)) {
                    throw IOException("JPEG encoding failed")
                }
            }
        } catch (failure: IOException) {
            target.delete()
            throw failure
        }
        return target
    }

    private fun photo(source: File): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
            val (width, height) = fitted(info.size.width, info.size.height)
            decoder.setTargetSize(width, height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    private fun firstPage(source: File): Bitmap? =
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { pdf ->
                if (pdf.pageCount == 0) return null
                pdf.openPage(0).use { page ->
                    val (width, height) = fitted(page.width, page.height)
                    createBitmap(width, height).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }

    companion object {
        const val FOLDER = "scan_previews"

        const val LONGEST_SIDE = 1280

        private const val QUALITY = 82

        private const val TAG = "ScanPreview"

        fun fitted(width: Int, height: Int): Pair<Int, Int> {
            val longest = max(width, height)
            if (longest <= LONGEST_SIDE || longest <= 0) return max(width, 1) to max(height, 1)
            val scale = LONGEST_SIDE.toDouble() / longest
            return max((width * scale).roundToInt(), 1) to max((height * scale).roundToInt(), 1)
        }
    }
}

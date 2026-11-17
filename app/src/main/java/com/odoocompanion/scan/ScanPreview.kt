package com.odoocompanion.scan

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

// A picture of what was sent, kept beside the scan log: the copy that is
// uploaded is deleted once Odoo has it, so the Documents card would otherwise
// have nothing left to show. A photo is shrunk as it is; a PDF shows its first
// page. Anything else has no picture.
class ScanPreview(private val folder: File) {
    /** A preview of [source] in the preview folder, or null when there is none to make. */
    fun render(source: File, mimetype: String): File? {
        val bitmap = try {
            when {
                mimetype == ScanIntake.PDF -> firstPage(source)
                mimetype.startsWith("image/") -> photo(source)
                else -> null
            }
        } catch (unreadable: IOException) {
            Log.w(TAG, "No preview for ${source.name}", unreadable)
            null
        } catch (unreadable: SecurityException) {
            Log.w(TAG, "No preview for ${source.name}", unreadable)
            null
        } catch (unreadable: IllegalArgumentException) {
            Log.w(TAG, "No preview for ${source.name}", unreadable)
            null
        } ?: return null
        folder.mkdirs()
        val target = File(folder, "${UUID.randomUUID()}.jpg")
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        bitmap.recycle()
        return target
    }

    // ImageDecoder turns the photo upright from its EXIF orientation and reads
    // HEIC, which BitmapFactory does neither of.
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
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
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

        /** [width] by [height] brought within [LONGEST_SIDE], never enlarged. */
        fun fitted(width: Int, height: Int): Pair<Int, Int> {
            val longest = max(width, height)
            if (longest <= LONGEST_SIDE || longest <= 0) return max(width, 1) to max(height, 1)
            val scale = LONGEST_SIDE.toDouble() / longest
            return max((width * scale).roundToInt(), 1) to max((height * scale).roundToInt(), 1)
        }
    }
}

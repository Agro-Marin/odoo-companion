package com.odoocompanion.recording

import android.media.MediaMetadataRetriever
import com.odoocompanion.system.debug
import java.io.File

@Suppress("TooGenericExceptionCaught")
internal fun audioDurationSeconds(file: File): Long? = try {
    MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(file.path)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?.let { millis -> (millis + MILLIS_PER_SECOND / 2) / MILLIS_PER_SECOND }
    }
} catch (unreadable: RuntimeException) {
    debug(TAG) { "no duration: ${unreadable.javaClass.simpleName}" }
    null
}

private const val MILLIS_PER_SECOND = 1_000L

private const val TAG = "RecordingHarvest"

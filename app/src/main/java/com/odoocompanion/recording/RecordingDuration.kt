package com.odoocompanion.recording

import android.media.MediaMetadataRetriever
import com.odoocompanion.system.debug
import java.io.File

// Whole seconds, or null when the container will not say -- a file the dialer
// is still writing, or one no decoder on this handset understands. The server
// stores 0 for an absent duration, so null is sent as nothing.
//
// RuntimeException, not something narrower: that is what setDataSource throws
// for media it cannot open ("setDataSource failed: status = 0x80000000"), and a
// recording is worth uploading without its length. Only the class is logged --
// the message can carry the path, and the filename carries the caller.
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

package com.odoocompanion.recording

import java.io.File

data class ScannedRecording(val file: File, val modifiedAt: Long)

class RecordingScanner(
    private val root: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    // Every settled recording on disk. There is deliberately no cursor: a
    // recording is filed by its write time, and a write time is a clock the
    // app does not control. A file stamped below wherever a cursor had reached
    // -- the phone's clock corrected backwards, a folder restored from a
    // backup, a dialer that stamps the call's start rather than its end -- was
    // never harvested, and nothing said so. What has already been handled is
    // the outbox's to answer, and it does: an uploaded file is deleted, a
    // failed one stays queued or dead, and either way it is not on this list
    // twice.
    fun scan(): List<ScannedRecording> {
        val settledBefore = now() - SETTLE_MILLIS
        return CANDIDATE_DIRECTORIES
            .map { File(root, it) }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().maxDepth(MAX_DEPTH) }
            .filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
            .filterNot { it.parentFile?.name?.lowercase() in NOT_CALL_DIRECTORIES }
            .distinctBy { it.absolutePath }
            .map { ScannedRecording(it, it.lastModified()) }
            .filter { it.modifiedAt <= settledBefore }
            .sortedBy { it.modifiedAt }
    }

    companion object {
        val CANDIDATE_DIRECTORIES = listOf(
            "Recordings/Call",
            "Sounds/Call",
            "Record/Call",
            "MIUI/sound_recorder/call_rec",
            "PhoneRecord",
            "Recordings",
        )

        val AUDIO_EXTENSIONS = setOf("m4a", "amr", "mp3", "3gp", "3gpp", "wav", "ogg", "mp4")

        val NOT_CALL_DIRECTORIES = setOf(
            "voice recorder",
            "voice_recorder",
            "voicerecorder",
            "sound recorder",
            "sound_recorder",
            "soundrecorder",
            "voice memos",
            "voice_memos",
            "voicememos",
            "voice",
        )

        private const val MAX_DEPTH = 2

        const val SETTLE_MILLIS = 60_000L
    }
}

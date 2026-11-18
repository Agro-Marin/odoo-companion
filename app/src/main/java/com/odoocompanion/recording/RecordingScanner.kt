package com.odoocompanion.recording

import com.odoocompanion.data.FileStamp
import com.odoocompanion.system.debug
import java.io.File
import java.io.IOException
import java.nio.file.Files

data class ScannedRecording(val file: File, val stamp: FileStamp)

class RecordingScanner(
    private val root: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun scan(): List<ScannedRecording> {
        val settledBefore = now() - SETTLE_MILLIS
        return CANDIDATE_DIRECTORIES.asSequence()
            .mapNotNull(::candidateDirectory)
            .flatMap { directory ->
                directory.walkTopDown().onEnter {
                    !Files.isSymbolicLink(it.toPath()) &&
                        it.name.lowercase() !in NOT_CALL_DIRECTORIES
                }.maxDepth(MAX_DEPTH)
            }
            .filter { it.extension.lowercase() in AUDIO_EXTENSIONS }
            .distinctBy { it.absolutePath }
            .mapNotNull { file ->
                try {
                    FileStamp.read(file)?.let { ScannedRecording(file, it) }
                } catch (failure: IOException) {
                    debug(TAG) {
                        "Could not read recording attributes (${failure.javaClass.simpleName})"
                    }
                    null
                } catch (_: SecurityException) {
                    debug(TAG) { "Permission to read recording attributes was denied" }
                    null
                }
            }
            .filter { it.stamp.modifiedAt <= settledBefore }
            .sortedBy { it.stamp.modifiedAt }
            .toList()
    }

    private fun candidateDirectory(relative: String): File? {
        var directory = root
        for (name in relative.split('/')) {
            directory = File(directory, name)
            if (Files.isSymbolicLink(directory.toPath())) return null
        }
        return directory.takeIf { it.isDirectory }
    }

    companion object {
        private const val TAG = "RecordingScanner"

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

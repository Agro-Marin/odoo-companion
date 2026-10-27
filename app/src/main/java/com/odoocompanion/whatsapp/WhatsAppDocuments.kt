package com.odoocompanion.whatsapp

import java.io.File

data class DocumentFile(val path: String, val name: String, val modifiedAt: Long, val size: Long)

// The documents WhatsApp has saved on this handset. A notice names a document
// but carries no file, and WhatsApp's own storage is closed to other apps, so
// the file is found where WhatsApp copies what it downloads. Reaching that
// folder takes all-files access, the same grant the recording harvest needs.
class WhatsAppDocuments(private val root: File) {
    fun scan(): List<DocumentFile> = CANDIDATE_DIRECTORIES
        .map { File(root, it) }
        .filter { it.isDirectory }
        .flatMap { it.walkTopDown().maxDepth(MAX_DEPTH) }
        .filter { it.isFile && !it.name.startsWith(".") }
        .filterNot { it.parentFile?.name?.lowercase() in NOT_RECEIVED_DIRECTORIES }
        .distinctBy { it.absolutePath }
        .map { DocumentFile(it.absolutePath, it.name, it.lastModified(), it.length()) }

    // The folders a received document can land in, for a FileObserver: one
    // does not see into subfolders, so each one the scan reads is listed.
    fun watchedDirectories(): List<File> = CANDIDATE_DIRECTORIES
        .map { File(root, it) }
        .filter { it.isDirectory }
        .flatMap { it.walkTopDown().maxDepth(MAX_DEPTH - 1).filter(File::isDirectory) }
        .filterNot { it.name.lowercase() in NOT_RECEIVED_DIRECTORIES }
        .distinctBy { it.absolutePath }

    companion object {
        // Android 11 moved WhatsApp's media under Android/media; the bare
        // folders are where Android 10 and older builds still write.
        val CANDIDATE_DIRECTORIES = listOf(
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/" +
                "WhatsApp Business Documents",
            "WhatsApp/Media/WhatsApp Documents",
            "WhatsApp Business/Media/WhatsApp Business Documents",
        )

        // What this handset sent, and what WhatsApp keeps out of the gallery.
        val NOT_RECEIVED_DIRECTORIES = setOf("sent", "private")

        private const val MAX_DEPTH = 2

        fun appOf(path: String): String = if (
            WhatsAppNotices.WHATSAPP_BUSINESS in path || "WhatsApp Business" in path
        ) {
            WhatsAppNotices.WHATSAPP_BUSINESS
        } else {
            WhatsAppNotices.WHATSAPP
        }
    }
}

object DocumentMatcher {
    // WhatsApp writes the file when it downloads it: just before the notice
    // when downloads are automatic, or whenever the person taps it otherwise.
    const val BEFORE_NOTICE_MILLIS = 10L * 60 * 1000
    const val AFTER_NOTICE_MILLIS = 7L * 24 * 60 * 60 * 1000

    // A caption names no file, so only a file written at the notice's own
    // moment is taken for it.
    const val CAPTION_SLACK_MILLIS = 2L * 60 * 1000

    private val EXTENSION = Regex("""\.[A-Za-z0-9]{1,5}$""")

    fun match(
        notice: DocumentNotice,
        files: List<DocumentFile>,
        claimed: Set<String>,
    ): DocumentFile? {
        val free = files.filterNot { it.path in claimed }
        val sameName = namePattern(notice.label)
        val candidates = if (sameName != null) {
            free.filter {
                sameName.matches(it.name) &&
                    it.modifiedAt - notice.at in -BEFORE_NOTICE_MILLIS..AFTER_NOTICE_MILLIS
            }
        } else {
            free.filter {
                it.modifiedAt - notice.at in -CAPTION_SLACK_MILLIS..CAPTION_SLACK_MILLIS
            }
        }
        return candidates.minByOrNull { kotlin.math.abs(it.modifiedAt - notice.at) }
    }

    // "Constancia.pdf", or "Constancia (1).pdf": the name WhatsApp gives a
    // second file of the same name.
    private fun namePattern(label: String): Regex? {
        val extension = EXTENSION.find(label)?.value ?: return null
        val stem = label.removeSuffix(extension)
        return Regex(
            Regex.escape(stem) + """(?: \(\d+\))?""" + Regex.escape(extension),
            RegexOption.IGNORE_CASE,
        )
    }
}

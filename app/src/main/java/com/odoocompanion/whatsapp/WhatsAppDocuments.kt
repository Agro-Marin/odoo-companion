package com.odoocompanion.whatsapp

import java.io.File

data class DocumentFile(val path: String, val name: String, val modifiedAt: Long, val size: Long)

class WhatsAppDocuments(private val root: File) {
    fun scan(): List<DocumentFile> = CANDIDATE_DIRECTORIES
        .map { File(root, it) }
        .filter { it.isDirectory }
        .flatMap { it.received().maxDepth(MAX_DEPTH) }
        .filter { it.isFile && !it.name.startsWith(".") }
        .distinctBy { it.absolutePath }
        .map { DocumentFile(it.absolutePath, it.name, it.lastModified(), it.length()) }

    fun watchedDirectories(): List<File> = CANDIDATE_DIRECTORIES
        .map { File(root, it) }
        .filter { it.isDirectory }
        .flatMap { it.received().maxDepth(MAX_DEPTH - 1).filter(File::isDirectory) }
        .filterNot { it.name.lowercase() in NOT_RECEIVED_DIRECTORIES }
        .distinctBy { it.absolutePath }

    private fun File.received(): FileTreeWalk =
        walkTopDown().onEnter { it.name.lowercase() !in NOT_RECEIVED_DIRECTORIES }

    companion object {
        val CANDIDATE_DIRECTORIES = listOf(
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/" +
                "WhatsApp Business Documents",
            "WhatsApp/Media/WhatsApp Documents",
            "WhatsApp Business/Media/WhatsApp Business Documents",
        )

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
    const val BEFORE_NOTICE_MILLIS = 10L * 60 * 1000
    const val AFTER_NOTICE_MILLIS = 7L * 24 * 60 * 60 * 1000

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

    private fun namePattern(label: String): Regex? {
        val extension = EXTENSION.find(label)?.value ?: return null
        val stem = label.removeSuffix(extension)
        return Regex(
            Regex.escape(stem) + """(?: \(\d+\))?""" + Regex.escape(extension),
            RegexOption.IGNORE_CASE,
        )
    }
}

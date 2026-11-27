package com.odoocompanion.whatsapp

import com.odoocompanion.data.FileStamp
import com.odoocompanion.data.directoryWithoutLinks
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

internal enum class WhatsAppInbox(val app: String, val source: String) {
    PERSONAL(WhatsAppNotices.WHATSAPP, "WhatsApp"),
    BUSINESS(WhatsAppNotices.WHATSAPP_BUSINESS, "WhatsApp Business");

    val legacy = "$source/Media/$source Documents"
    val modern = "Android/media/$app/$legacy"

    companion object {
        fun containing(path: String): WhatsAppInbox? {
            val parent = File(path).toPath().parent ?: return null
            return entries.firstOrNull {
                parent.endsWith(it.legacy) || parent.parent?.endsWith(it.legacy) == true
            }
        }
    }
}

data class DocumentFile(
    val path: String,
    val name: String,
    val modifiedAt: Long,
    val size: Long,
    val app: String = WhatsAppNotices.WHATSAPP,
) {
    val stamp: FileStamp get() = FileStamp(size, modifiedAt)

    private val sha256: String? by lazy(LazyThreadSafetyMode.NONE) {
        try {
            DocumentSnapshot.digest(File(path), size)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    fun matches(source: FileStamp?): Boolean =
        source != null && stamp.matchesQueuedMetadata(source, modifiedAt) &&
            source.sha256 != null && source.sha256 == sha256
}

class WhatsAppDocuments(private val root: File) {
    fun scan(): List<DocumentFile> = inboxes.flatMap { (relative, inbox) ->
        val directory = root.directoryWithoutLinks(relative) ?: return@flatMap emptyList()
        directory.received().maxDepth(MAX_DEPTH)
            .filter {
                !it.name.startsWith(".") &&
                    Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS)
            }
            .map {
                DocumentFile(it.absolutePath, it.name, it.lastModified(), it.length(), inbox.app)
            }
            .toList()
    }

    fun watchedDirectories(): List<File> = inboxes.keys
        .mapNotNull(root::directoryWithoutLinks)
        .flatMap { it.received().maxDepth(MAX_DEPTH - 1) }
        .filter { Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
        .filterNot { it.name.lowercase() in NOT_RECEIVED_DIRECTORIES }
        .distinctBy { it.absolutePath }

    private fun File.received(): FileTreeWalk = walkTopDown().onEnter {
        !Files.isSymbolicLink(it.toPath()) && it.name.lowercase() !in NOT_RECEIVED_DIRECTORIES
    }

    companion object {
        private val inboxes = WhatsAppInbox.entries.associateBy { it.modern } +
            WhatsAppInbox.entries.associateBy { it.legacy }

        val NOT_RECEIVED_DIRECTORIES = setOf("sent", "private")

        private const val MAX_DEPTH = 2
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
    ): DocumentFile? = closest(notice, files, claimed).singleOrNull()

    internal fun closest(
        notice: DocumentNotice,
        files: List<DocumentFile>,
        claimed: Set<String>,
    ): List<DocumentFile> {
        val free = files.filter { it.app == notice.app && it.path !in claimed }
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
        val distance =
            candidates.minOfOrNull { kotlin.math.abs(it.modifiedAt - notice.at) }
                ?: return emptyList()
        return candidates.filter { kotlin.math.abs(it.modifiedAt - notice.at) == distance }
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

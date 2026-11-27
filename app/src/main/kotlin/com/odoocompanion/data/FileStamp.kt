package com.odoocompanion.data

import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

@Serializable
data class FileStamp(val size: Long, val modifiedAt: Long, val sha256: String? = null) {
    fun matchesQueuedMetadata(queued: FileStamp?, queuedAt: Long): Boolean = if (queued ==
        null
    ) {
        modifiedAt <= queuedAt
    } else {
        size == queued.size && modifiedAt == queued.modifiedAt
    }

    companion object {
        fun read(file: File): FileStamp? {
            val attributes = Files.readAttributes(
                file.toPath(),
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            return if (attributes.isRegularFile) {
                FileStamp(attributes.size(), attributes.lastModifiedTime().toMillis())
            } else {
                null
            }
        }
    }
}

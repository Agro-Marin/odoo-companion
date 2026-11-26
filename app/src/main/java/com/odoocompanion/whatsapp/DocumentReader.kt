package com.odoocompanion.whatsapp

import android.util.Log
import com.odoocompanion.data.FileStamp
import com.odoocompanion.data.OutboxLimits
import okio.ByteString.Companion.toByteString
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

internal object DocumentSnapshot {
    inline fun <T> use(
        file: File,
        size: Long,
        cache: File,
        block: (RandomAccessFile, String) -> T
    ): T {
        val path = Files.createTempFile(cache.toPath(), "document-", ".tmp")
        try {
            RandomAccessFile(path.toFile(), "rw").use { copy ->
                Files.delete(path)
                val sha256 = digest(file, size, copy)
                copy.seek(0)
                return block(copy, sha256)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    fun digest(file: File, size: Long, copy: RandomAccessFile? = null): String {
        if (size !in 0..OutboxLimits.MAX_FILE_BYTES) throw IOException("Document exceeds limit")
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var count = 0L
        Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                count += n
                if (count > size) throw IOException("Document grew while reading")
                digest.update(buffer, 0, n)
                copy?.write(buffer, 0, n)
            }
        }
        if (count != size) throw IOException("Document shrank while reading")
        return digest.digest().toByteString().hex()
    }
}

class DocumentReader(
    private val cache: File,
    private val readPdf: (FileDescriptor) -> Classification? = PageReader()::classify,
) {
    fun classify(file: File): Classification? {
        if (!file.isPdf()) return Classification(DocumentKind.OTHER)
        return try {
            val source = FileStamp.read(file) ?: return null
            if (source.size > OutboxLimits.MAX_FILE_BYTES) {
                return FileInputStream(file).use { readPdf(it.fd)?.copy(source = source) }
            }
            DocumentSnapshot.use(file, source.size, cache) { copy, sha256 ->
                readPdf(copy.fd)?.copy(source = source.copy(sha256 = sha256))
            }
        } catch (failure: IOException) {
            Log.w(TAG, "Could not read a document (${failure.javaClass.simpleName})")
            null
        } catch (_: SecurityException) {
            Log.w(TAG, "Document access denied")
            null
        }
    }

    internal suspend fun <T : Any> withVerifiedContent(
        file: File,
        size: Long,
        sha256: String?,
        document: QueuedDocument,
        pinRechecked: suspend (String) -> Unit,
        send: suspend (InputStream) -> T,
    ): T? = DocumentSnapshot.use(file, size, cache) { copy, actual ->
        if (sha256 != null) {
            if (actual != sha256) return null
        } else {
            if (!file.isPdf()) return null
            val read = readPdf(copy.fd) ?: throw IOException("Document reading unavailable")
            if (!document.agreesWith(read)) return null
            pinRechecked(actual)
        }
        copy.seek(0)
        send(Channels.newInputStream(copy.channel))
    }

    private fun File.isPdf(): Boolean = extension.equals("pdf", ignoreCase = true)

    private companion object {
        const val TAG = "DocumentReader"
    }
}

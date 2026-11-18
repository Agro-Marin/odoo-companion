package com.odoocompanion.net

import com.odoocompanion.data.FileStamp
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Base64

internal class FileBody private constructor(
    private val prefix: ByteArray,
    private val contentType: MediaType,
    private val sourceBytes: Long,
    private val open: () -> InputStream,
    private val unchanged: () -> Boolean,
) : RequestBody() {
    constructor(
        metadata: JsonObject,
        file: File,
        contentType: MediaType,
        fileKey: String = AUDIO_KEY,
        stamp: FileStamp? = null,
    ) : this(
        buildPrefix(metadata, fileKey),
        contentType,
        stamp?.size ?: file.length(),
        { Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS) },
        { stamp == null || FileStamp.read(file) == stamp },
    )

    fun fromVerifiedCopy(input: InputStream): FileBody =
        FileBody(prefix, contentType, sourceBytes, { input }, { true })

    override fun contentType(): MediaType = contentType

    override fun isOneShot(): Boolean = true

    override fun contentLength(): Long = prefix.size + base64Length(sourceBytes) + SUFFIX.size

    override fun writeTo(sink: BufferedSink) {
        sink.write(prefix)
        fromFile(open).use { input ->
            val chunk = ByteArray(minOf(sourceBytes, CHUNK_BYTES.toLong()).toInt())
            val encoded = ByteArray(base64Length(chunk.size.toLong()).toInt())
            var remaining = sourceBytes
            while (remaining > 0) {
                val read = minOf(chunk.size.toLong(), remaining).toInt()
                fromFile { readFully(input, chunk, read) }
                val source = if (read == chunk.size) chunk else chunk.copyOf(read)
                sink.write(encoded, 0, BASE64.encode(source, encoded))
                remaining -= read
            }
            val trailingByte = fromFile { input.read() }
            if (trailingByte != -1) {
                throw UploadSourceUnavailable("file grew during upload")
            }
        }
        if (!fromFile(unchanged)) {
            throw UploadSourceUnavailable("file changed during upload")
        }
        sink.write(SUFFIX)
    }

    private fun readFully(input: InputStream, buffer: ByteArray, size: Int) {
        var total = 0
        while (total < size) {
            val read = input.read(buffer, total, size - total)
            if (read < 0) throw java.io.EOFException()
            total += read
        }
    }

    private inline fun <T> fromFile(read: () -> T): T = try {
        read()
    } catch (failure: IOException) {
        throw UploadSourceUnavailable(
            "could not read upload file (${failure.javaClass.simpleName})",
            failure
        )
    } catch (_: SecurityException) {
        throw UploadSourceUnavailable("permission to read upload file was denied")
    }

    companion object {
        const val AUDIO_KEY = "audio_b64"
        const val FILE_KEY = "file_b64"

        private val BASE64: Base64.Encoder = Base64.getEncoder()

        private val SUFFIX = "\"}".toByteArray(Charsets.UTF_8)

        private const val CHUNK_BYTES = 3 * 16 * 1024

        private fun buildPrefix(metadata: JsonObject, fileKey: String): ByteArray {
            val separator = if (metadata.isEmpty()) "" else ","
            val opened = metadata.toString().dropLast(1)
            return """$opened$separator"$fileKey":"""".toByteArray(Charsets.UTF_8)
        }

        private fun base64Length(bytes: Long): Long = 4 * ((bytes + 2) / 3)
    }
}

internal class UploadSourceUnavailable(reason: String, cause: Throwable? = null) :
    IOException(reason, cause)

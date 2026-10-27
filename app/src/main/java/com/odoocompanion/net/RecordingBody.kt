package com.odoocompanion.net

import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.InputStream
import java.util.Base64

// A JSON object whose last member is a file, base64-encoded as it is streamed
// so a file of tens of megabytes is never held in memory. The member is
// audio_b64 for a recording and file_b64 for a document.
internal class RecordingBody(
    metadata: JsonObject,
    private val file: File,
    private val contentType: MediaType,
    private val fileKey: String = AUDIO_KEY,
) : RequestBody() {
    private val prefix = buildPrefix(metadata)

    override fun contentType(): MediaType = contentType

    override fun isOneShot(): Boolean = true

    override fun contentLength(): Long = prefix.size + base64Length(file.length()) + SUFFIX.size

    override fun writeTo(sink: BufferedSink) {
        sink.write(prefix)
        file.inputStream().use { input ->
            val chunk = ByteArray(CHUNK_BYTES)
            while (true) {
                val read = fill(input, chunk)
                if (read == 0) break
                sink.write(BASE64.encode(if (read == CHUNK_BYTES) chunk else chunk.copyOf(read)))
                if (read < CHUNK_BYTES) break
            }
        }
        sink.write(SUFFIX)
    }

    private fun fill(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    private fun buildPrefix(metadata: JsonObject): ByteArray {
        val separator = if (metadata.isEmpty()) "" else ","
        val opened = metadata.toString().dropLast(1)
        return """$opened$separator"$fileKey":"""".toByteArray(Charsets.UTF_8)
    }

    companion object {
        const val AUDIO_KEY = "audio_b64"
        const val FILE_KEY = "file_b64"

        private val BASE64: Base64.Encoder = Base64.getEncoder()

        private val SUFFIX = "\"}".toByteArray(Charsets.UTF_8)

        private const val CHUNK_BYTES = 3 * 16 * 1024

        private fun base64Length(bytes: Long): Long = 4 * ((bytes + 2) / 3)
    }
}

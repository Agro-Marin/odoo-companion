package com.odoocompanion.net

import com.odoocompanion.config.Settings
import com.odoocompanion.data.OutboxLimits
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Base64

class FileBodyTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun settings() = Settings(
        baseUrl = server.url("/").toString().trimEnd('/'),
        identifier = "phone-01",
        token = "t",
    )

    private fun metadata(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun recordingOf(bytes: Int): File {
        val file = File.createTempFile("rec", ".m4a")
        file.outputStream().use { out ->
            val chunk = ByteArray(1 shl 20) { (it % 251).toByte() }
            var written = 0
            while (written < bytes) {
                val n = minOf(chunk.size, bytes - written)
                out.write(chunk, 0, n)
                written += n
            }
        }
        return file
    }

    @Test
    fun `a recording at the size cap uploads without exhausting the heap`() = runTest {
        server.bodyLimit = 0
        server.enqueue(MockResponse(body = """{"status":"success"}"""))
        val meta = """{"number":"+525512345678"}"""
        val file = recordingOf(OutboxLimits.MAX_FILE_BYTES.toInt())

        val outcome = OdooClient().uploads(settings()).post("recording", file, metadata(meta))

        assertEquals(true, outcome is UploadOutcome.Success)

        val envelope = meta.dropLast(1).length + ""","audio_b64":"""".length + """"}""".length
        assertEquals(
            envelope + 4 * (OutboxLimits.MAX_FILE_BYTES / 3),
            server.takeRequest().bodySize,
        )
        file.delete()
    }

    @Test
    fun `the streamed audio decodes back to the original bytes`() = runTest {
        server.enqueue(MockResponse(body = "{}"))

        val file = recordingOf(3 * 16 * 1024 * 2 + 1234)
        val original = file.readBytes()

        OdooClient().uploads(settings()).post("recording", file, metadata("""{"number":"+52"}"""))

        val sent = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertArrayEquals(
            original,
            Base64.getDecoder().decode(sent.getValue("audio_b64").jsonPrimitive.content),
        )
        assertEquals("+52", sent.getValue("number").jsonPrimitive.content)
        file.delete()
    }

    @Test
    fun `a recording body is sent once per attempt, never re-streamed by the transport`() {
        val body = FileBody(
            metadata("""{"number":"+52"}"""),
            recordingOf(16),
            "application/json".toMediaType(),
        )

        assertTrue(
            "OkHttp would otherwise replay the whole base64 body after a mid-stream failure",
            body.isOneShot(),
        )
    }

    @Test
    fun `the upload opens only a regular source at the selected path`() {
        val directory = Files.createTempDirectory("selected-source").toFile()
        try {
            val selected = File(directory, "selected.pdf").apply { writeText("old") }
            val other = File(directory, "other.pdf").apply { writeText("new") }
            val body = FileBody(metadata("{}"), selected, "application/json".toMediaType())
            Files.delete(selected.toPath())
            Files.createSymbolicLink(selected.toPath(), other.toPath())

            val sent = Buffer()
            assertThrows(UploadSourceUnavailable::class.java) { body.writeTo(sent) }
            assertEquals("""{"audio_b64":"""", sent.readUtf8())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `an empty metadata object still produces valid json`() = runTest {
        server.enqueue(MockResponse(body = "{}"))
        val file = recordingOf(64)

        OdooClient().uploads(settings()).post("recording", file, metadata("{}"))

        val sent = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals(setOf("audio_b64"), sent.keys)
        file.delete()
    }

    @Test
    fun `the declared content length matches the bytes sent`() = runTest {
        server.enqueue(MockResponse(body = "{}"))
        val file = recordingOf(100_000)

        OdooClient().uploads(
            settings()
        ).post("recording", file, metadata("""{"file_name":"a.m4a"}"""))

        val request = server.takeRequest()
        assertEquals(request.bodySize, request.headers["Content-Length"]!!.toLong())
        assertEquals(null, request.headers["Transfer-Encoding"])
        file.delete()
    }

    @Test
    fun `a file changing size within the same base64 quartet cannot complete the request`() {
        for (changedSize in listOf(1, 3)) {
            val file = recordingOf(2)
            try {
                val body = FileBody(metadata("{}"), file, "application/json".toMediaType())
                val declared = body.contentLength()
                file.writeBytes(ByteArray(changedSize))
                assertEquals(declared, body.contentLength())
                assertThrows(IOException::class.java) { body.writeTo(Buffer()) }
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun `the declared length matches the bytes written at every chunk boundary`() = runTest {
        val chunk = 3 * 16 * 1024
        val sizes = listOf(
            0, 1, 2, 3, 4, 5,
            chunk - 1, chunk, chunk + 1,
            2 * chunk - 1, 2 * chunk, 2 * chunk + 1,
            3 * chunk + 7,
        )
        for (size in sizes) {
            server.enqueue(MockResponse(body = "{}"))
            val file = recordingOf(size)

            OdooClient().uploads(settings()).post("recording", file, metadata("""{"n":"1"}"""))

            val request = server.takeRequest()
            assertEquals(
                "declared length disagrees with the body at $size bytes",
                request.bodySize,
                request.headers["Content-Length"]!!.toLong(),
            )
            assertEquals(
                "audio did not survive the round trip at $size bytes",
                size,
                Base64.getDecoder().decode(
                    Json.parseToJsonElement(request.body!!.utf8())
                        .jsonObject.getValue("audio_b64").jsonPrimitive.content,
                ).size,
            )
            file.delete()
        }
    }
}

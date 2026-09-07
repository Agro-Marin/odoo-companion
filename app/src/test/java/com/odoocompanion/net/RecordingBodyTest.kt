package com.odoocompanion.net

import com.odoocompanion.config.Settings
import com.odoocompanion.sync.OutboxDrainer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Base64

class RecordingBodyTest {
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
        server.enqueue(MockResponse(body = """{"status":"success"}"""))
        val meta = """{"number":"+525512345678"}"""
        val file = recordingOf(OutboxDrainer.MAX_RECORDING_BYTES.toInt())

        val outcome = OdooClient().post(settings(), "recording", file, metadata(meta))

        assertEquals(true, outcome is UploadOutcome.Success)

        val envelope = meta.dropLast(1).length + ""","audio_b64":"""".length + """"}""".length
        assertEquals(
            envelope + 4 * (OutboxDrainer.MAX_RECORDING_BYTES / 3),
            server.takeRequest().bodySize,
        )
        file.delete()
    }

    @Test
    fun `the streamed audio decodes back to the original bytes`() = runTest {
        server.enqueue(MockResponse(body = "{}"))

        val file = recordingOf(3 * 16 * 1024 * 2 + 1234)
        val original = file.readBytes()

        OdooClient().post(settings(), "recording", file, metadata("""{"number":"+52"}"""))

        val sent = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertArrayEquals(
            original,
            Base64.getDecoder().decode(sent.getValue("audio_b64").jsonPrimitive.content),
        )
        assertEquals("+52", sent.getValue("number").jsonPrimitive.content)
        file.delete()
    }

    @Test
    fun `an empty metadata object still produces valid json`() = runTest {
        server.enqueue(MockResponse(body = "{}"))
        val file = recordingOf(64)

        OdooClient().post(settings(), "recording", file, metadata("{}"))

        val sent = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals(setOf("audio_b64"), sent.keys)
        file.delete()
    }

    @Test
    fun `the declared content length matches the bytes sent`() = runTest {
        server.enqueue(MockResponse(body = "{}"))
        val file = recordingOf(100_000)

        OdooClient().post(settings(), "recording", file, metadata("""{"file_name":"a.m4a"}"""))

        val request = server.takeRequest()
        assertEquals(request.bodySize, request.headers["Content-Length"]!!.toLong())
        assertEquals(null, request.headers["Transfer-Encoding"])
        file.delete()
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

            OdooClient().post(settings(), "recording", file, metadata("""{"n":"1"}"""))

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

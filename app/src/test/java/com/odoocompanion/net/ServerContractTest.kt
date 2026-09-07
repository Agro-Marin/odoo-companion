package com.odoocompanion.net

import com.odoocompanion.config.Settings
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ServerContractTest {
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
        identifier = "SMOKE-25",
        token = "t",
    )

    private suspend fun classify(code: Int, body: String): UploadOutcome {
        server.enqueue(MockResponse(code = code, body = body))
        return OdooClient().post(settings(), "location", "{}")
    }

    @Test
    fun `the location endpoint's success`() = runTest {
        val outcome = classify(
            200,
            """{"status": "success", "accepted": 2, "duplicates": 0, "skipped": 0,""" +
                """ "device_name": "Smoke Phone"}""",
        )
        assertEquals(UploadOutcome.Success(2, 0, 0), outcome)
    }

    @Test
    fun `the call log endpoint's success carries a matched count the client ignores`() = runTest {
        val outcome = classify(
            200,
            """{"status": "success", "accepted": 1, "duplicates": 0, "skipped": 0,""" +
                """ "matched": 0, "device_name": "Smoke Phone"}""",
        )
        assertEquals(UploadOutcome.Success(1, 0, 0), outcome)
    }

    @Test
    fun `the recording endpoint's success counts nothing at all`() = runTest {
        val outcome = classify(
            200,
            """{"status": "success", "recording_id": 15, "matched": true,""" +
                """ "device_name": "Smoke Phone"}""",
        )
        assertEquals(
            "no accepted key on this route, so a count can never be the discriminator " +
                "for a delivery -- only \"status\" can",
            UploadOutcome.Success(0, 0, 0),
            outcome,
        )
    }

    @Test
    fun `the transport layer's duplicate, inside the window`() = runTest {
        val outcome = classify(
            409,
            """{"error": "duplicate_event", "message": "Duplicate event detected"}""",
        )
        assertEquals(UploadOutcome.Duplicate, outcome)
    }

    @Test
    fun `the model layer's duplicate, once the window has passed`() = runTest {
        val outcome = classify(
            200,
            """{"status": "success", "accepted": 0, "duplicates": 1, "skipped": 0,""" +
                """ "matched": 0, "device_name": "Smoke Phone"}""",
        )
        assertEquals(UploadOutcome.Success(0, 1, 0), outcome)
    }

    @Test
    fun `a batch the server read and could not store`() = runTest {
        val outcome = classify(
            422,
            """{"status": "success", "accepted": 0, "duplicates": 0, "skipped": 1,""" +
                """ "device_name": "Smoke Phone"}""",
        )
        assertEquals(UploadOutcome.Rejected(422), outcome)
    }

    @Test
    fun `a payload over the device's cap, with the cap in the body`() = runTest {
        val outcome = classify(
            413,
            """{"error": "payload_too_large",""" +
                """ "message": "Request exceeds maximum size of 2KB"}""",
        )
        assertEquals(
            "the device's max_payload_size was 2048 bytes; the client must read it back",
            UploadOutcome.TooLarge(2048),
            outcome,
        )
    }

    @Test
    fun `a rejected token`() = runTest {
        val outcome = classify(
            401,
            """{"error": "authentication_failed", "message": "Invalid credentials"}""",
        )
        assertTrue(outcome is UploadOutcome.Retry)
    }
}

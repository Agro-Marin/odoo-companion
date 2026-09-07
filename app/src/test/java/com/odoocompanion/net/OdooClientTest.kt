package com.odoocompanion.net

import android.app.Application
import com.odoocompanion.config.Settings
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OdooClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun settings() = Settings(
        baseUrl = server.url("/").toString().trimEnd('/'),
        identifier = "phone-01",
        token = "secret-token",
    )

    @Test
    fun `posts to the device endpoint with a bearer token`() = runTest {
        server.enqueue(MockResponse(body = """{"status":"success","accepted":2}"""))

        val outcome = OdooClient().post(settings(), "location", """{"points":[]}""")

        val request = server.takeRequest()
        assertEquals("/remote/mobile/phone-01/location", request.target)
        assertEquals("Bearer secret-token", request.headers["Authorization"])
        assertTrue(outcome is UploadOutcome.Success)
        assertEquals(2, (outcome as UploadOutcome.Success).accepted)
    }

    @Test
    fun `a trailing slash on the base url does not double up`() = runTest {
        server.enqueue(MockResponse(body = """{"accepted":0}"""))

        OdooClient().post(settings().copy(baseUrl = server.url("/").toString()), "calllog", "{}")

        assertEquals("/remote/mobile/phone-01/calllog", server.takeRequest().target)
    }

    @Test
    fun `a 200 that is not an Odoo reply is retried, not counted as delivery`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .setHeader("Content-Type", "text/html")
                .body("<html><body>Sign in to use this WiFi</body></html>")
                .build(),
        )

        val outcome = OdooClient().post(settings(), "calllog", "{}")

        assertTrue("$outcome", outcome is UploadOutcome.Retry)
    }

    @Test
    fun `a refusal that is not an Odoo reply is retried, not treated as refused`() = runTest {
        server.enqueue(MockResponse(code = 400, body = "Bad Request"))
        server.enqueue(MockResponse(code = 422, body = ""))

        val client = OdooClient()

        assertTrue(client.post(settings(), "calllog", "{}") is UploadOutcome.Retry)
        assertTrue(client.post(settings(), "calllog", "{}") is UploadOutcome.Retry)
    }

    @Test
    fun `a refusal Odoo itself sent is still a refusal`() = runTest {
        server.enqueue(
            MockResponse(
                code = 400,
                body = """{"error":"no_calls","message":"No call entries in payload"}""",
            ),
        )

        val outcome = OdooClient().post(settings(), "calllog", "{}")

        assertEquals(UploadOutcome.Rejected(400), outcome)
    }

    @Test
    fun `server errors and rate limits are retryable`() = runTest {
        server.enqueue(MockResponse(code = 500))
        server.enqueue(MockResponse(code = 429))

        val client = OdooClient()
        assertTrue(client.post(settings(), "location", "{}") is UploadOutcome.Retry)
        assertTrue(client.post(settings(), "location", "{}") is UploadOutcome.Retry)
    }

    @Test
    fun `a bad token is retryable so queued data survives a re-enrollment`() = runTest {
        server.enqueue(MockResponse(code = 401))

        assertTrue(OdooClient().post(settings(), "location", "{}") is UploadOutcome.Retry)
    }

    @Test
    fun `a base url with no scheme is retryable rather than thrown`() = runTest {
        val outcome = OdooClient().post(
            settings().copy(baseUrl = "odoo.example.com"),
            "location",
            "{}",
        )

        assertTrue(outcome is UploadOutcome.Retry)
    }

    @Test
    fun `an unprocessable payload is rejected rather than retried forever`() = runTest {
        server.enqueue(MockResponse(code = 422, body = """{"error":"no_points"}"""))

        val outcome = OdooClient().post(settings(), "location", "{}")

        assertTrue(outcome is UploadOutcome.Rejected)
        assertEquals(422, (outcome as UploadOutcome.Rejected).code)
    }
}

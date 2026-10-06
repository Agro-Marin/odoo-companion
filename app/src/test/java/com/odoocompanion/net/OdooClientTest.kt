package com.odoocompanion.net

import android.app.Application
import com.odoocompanion.config.Settings
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `a reply that names the service is remembered, and from then on required`() = runTest {
        var learned = 0
        val client = OdooClient(onServerNamedItself = { learned++ })
        server.enqueue(
            MockResponse(body = """{"service":"remote_mobile","status":"success","accepted":1}"""),
        )
        server.enqueue(MockResponse(body = """{"status":"success","accepted":1}"""))
        server.enqueue(MockResponse(code = 400, body = """{"error":"bad_request"}"""))
        server.enqueue(
            MockResponse(code = 400, body = """{"service":"remote_mobile","error":"no_calls"}"""),
        )

        assertTrue(client.post(settings(), "calllog", "{}") is UploadOutcome.Success)
        assertEquals(1, learned)

        val strict = settings().copy(serverNamesItself = true)
        assertTrue(
            "a gateway's own success envelope no longer deletes anything",
            client.post(strict, "calllog", "{}") is UploadOutcome.Retry,
        )
        assertTrue(
            "nor does a gateway's own refusal dead-letter anything",
            client.post(strict, "calllog", "{}") is UploadOutcome.Retry,
        )
        assertEquals(UploadOutcome.Rejected(400), client.post(strict, "calllog", "{}"))
        assertEquals("learned once, not on every reply", 1, learned)
    }

    @Test
    fun `an older server that does not name itself is still believed`() = runTest {
        var learned = 0
        server.enqueue(MockResponse(body = """{"status":"success","accepted":1}"""))

        val outcome = OdooClient(onServerNamedItself = {
            learned++
        }).post(settings(), "calllog", "{}")

        assertTrue(outcome is UploadOutcome.Success)
        assertEquals(0, learned)
    }

    private suspend fun linkDownFor(response: MockResponse): Boolean {
        server.enqueue(response)
        return (OdooClient().post(settings(), "calllog", "{}") as UploadOutcome.Retry).linkDown
    }

    // Which retries end a drain pass: the ones the next request would meet too.
    @Test
    fun `a failure that answers for every request is told apart from one that does not`() =
        runTest {
            val odoo = """{"service":"remote_mobile","error":"x"}"""
            for (code in listOf(401, 403, 404, 429, 503)) {
                assertTrue("$code", linkDownFor(MockResponse(code = code, body = odoo)))
            }
            for (code in listOf(500, 502, 504)) {
                assertFalse("$code", linkDownFor(MockResponse(code = code, body = odoo)))
            }
            assertTrue("a portal", linkDownFor(MockResponse(body = "<html>login</html>")))
        }

    // A 401 or a 404 is about the device and its token only when Odoo says it.
    // A firewall's own 403 page is about this request's content, and a proxy's
    // 503 says the server behind it is down, whatever page it serves.
    @Test
    fun `only Odoo's refusal of the device ends the pass, a firewall's page does not`() = runTest {
        val page = "<html>Request blocked</html>"
        for (code in listOf(401, 403, 404, 429)) {
            assertFalse("$code page", linkDownFor(MockResponse(code = code, body = page)))
        }
        assertTrue("503 page", linkDownFor(MockResponse(code = 503, body = page)))
    }

    @Test
    fun `a refused connection is the link, a read timeout is the request`() = runTest {
        val refused = OdooClient().post(
            settings().copy(baseUrl = "http://127.0.0.1:1"),
            "calllog",
            "{}",
        ) as UploadOutcome.Retry
        assertTrue(refused.reason, refused.linkDown)

        server.enqueue(
            MockResponse.Builder()
                .body("""{"status":"success"}""")
                .headersDelay(2, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
        )
        val impatient = OkHttpClient.Builder()
            .readTimeout(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        val timedOut = OdooClient(impatient).post(settings(), "calllog", "{}")
            as UploadOutcome.Retry
        assertFalse(timedOut.reason, timedOut.linkDown)
    }
}

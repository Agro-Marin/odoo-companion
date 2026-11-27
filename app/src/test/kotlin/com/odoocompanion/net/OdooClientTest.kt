package com.odoocompanion.net

import android.app.Application
import com.odoocompanion.config.Settings
import com.odoocompanion.push.PushKeyLookup
import com.odoocompanion.softphone.SoftphoneLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

        val outcome = OdooClient().uploads(settings()).post("location", """{"points":[]}""")

        val request = server.takeRequest()
        assertEquals("/remote/mobile/phone-01/location", request.target)
        assertEquals("Bearer secret-token", request.headers["Authorization"])
        assertTrue(outcome is UploadOutcome.Success)
        assertEquals(2, (outcome as UploadOutcome.Success).accepted)
    }

    @Test
    fun `a redirected upload stays pending at the configured endpoint`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(307).setHeader("Location", "/moved").build(),
        )
        server.enqueue(MockResponse(body = """{"status":"success","accepted":1}"""))

        val outcome = OdooClient().uploads(settings()).post("location", "{}")

        assertTrue(outcome is UploadOutcome.Retry)
        assertEquals(RetryScope.LINK, (outcome as UploadOutcome.Retry).scope)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `injected transports keep redirects at the original endpoint`() = runTest {
        server.enqueue(MockResponse.Builder().code(302).setHeader("Location", "/moved").build())
        server.enqueue(MockResponse(body = """{"status":"success","accepted":1}"""))

        val result = OdooClient(OkHttpClient()).uploads(settings()).post("calllog", "{}")

        assertTrue(result is UploadOutcome.Retry && result.scope == RetryScope.LINK)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a redirect body cannot establish the server capability`() = runTest {
        server.enqueue(MockResponse(code = 302, body = """{"service":"remote_mobile"}"""))
        var learned = false
        val client = OdooClient(onServerNamedItself = { learned = true })

        val result = client.uploads(settings()).post("location", "{}")

        assertTrue(result is UploadOutcome.Retry && result.scope == RetryScope.LINK)
        assertFalse(learned)
    }

    @Test
    fun `cancelling an upload while reading the reply cancels its network call`() = runBlocking {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var active: Call
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun responseBodyStart(call: Call) {
                active = call
                reading.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }).build()
        server.enqueue(MockResponse(body = """{"accepted":1}"""))
        val job = async(Dispatchers.IO) {
            OdooClient(http).uploads(settings()).post("calllog", "{}")
        }
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            job.cancel()
            assertTrue(active.isCanceled())
        } finally {
            release.countDown()
        }
        job.join()
    }

    @Test
    fun `a trailing slash on the base url does not double up`() = runTest {
        server.enqueue(MockResponse(body = """{"accepted":0}"""))

        OdooClient().uploads(
            settings().copy(baseUrl = server.url("/").toString())
        ).post("calllog", "{}")

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

        val outcome = OdooClient().uploads(settings()).post("calllog", "{}")

        assertTrue("$outcome", outcome is UploadOutcome.Retry)
    }

    @Test
    fun `a refusal that is not an Odoo reply is retried, not treated as refused`() = runTest {
        server.enqueue(MockResponse(code = 400, body = "Bad Request"))
        server.enqueue(MockResponse(code = 422, body = ""))

        val client = OdooClient()

        assertTrue(client.uploads(settings()).post("calllog", "{}") is UploadOutcome.Retry)
        assertTrue(client.uploads(settings()).post("calllog", "{}") is UploadOutcome.Retry)
    }

    @Test
    fun `a refusal Odoo itself sent is still a refusal`() = runTest {
        server.enqueue(
            MockResponse(
                code = 400,
                body = """{"error":"no_calls","message":"No call entries in payload"}""",
            ),
        )

        val outcome = OdooClient().uploads(settings()).post("calllog", "{}")

        assertEquals(UploadOutcome.Rejected(400), outcome)
    }

    @Test
    fun `server errors and rate limits are retryable`() = runTest {
        server.enqueue(MockResponse(code = 500))
        server.enqueue(MockResponse(code = 429))

        val client = OdooClient()
        assertTrue(client.uploads(settings()).post("location", "{}") is UploadOutcome.Retry)
        assertTrue(client.uploads(settings()).post("location", "{}") is UploadOutcome.Retry)
    }

    @Test
    fun `a bad token is retryable so queued data survives a re-enrollment`() = runTest {
        server.enqueue(MockResponse(code = 401))

        assertTrue(OdooClient().uploads(settings()).post("location", "{}") is UploadOutcome.Retry)
    }

    @Test
    fun `a base url with no scheme is retryable rather than thrown`() = runTest {
        val outcome = OdooClient().uploads(settings().copy(baseUrl = "odoo.example.com")).post(
            "location",
            "{}",
        )

        assertTrue(outcome is UploadOutcome.Retry)
    }

    @Test
    fun `an unprocessable payload is rejected rather than retried forever`() = runTest {
        server.enqueue(MockResponse(code = 422, body = """{"error":"no_points"}"""))

        val outcome = OdooClient().uploads(settings()).post("location", "{}")

        assertTrue(outcome is UploadOutcome.Rejected)
        assertEquals(422, (outcome as UploadOutcome.Rejected).code)
    }

    @Test
    fun `a reply that names the service is remembered, and from then on required`() = runTest {
        var learned = 0
        val uploads = OdooClient(onServerNamedItself = { learned++ }).uploads(settings())
        server.enqueue(
            MockResponse(body = """{"service":"remote_mobile","status":"success","accepted":1}"""),
        )
        server.enqueue(MockResponse(body = """{"status":"success","accepted":1}"""))
        server.enqueue(MockResponse(code = 400, body = """{"error":"bad_request"}"""))
        server.enqueue(
            MockResponse(code = 400, body = """{"service":"remote_mobile","error":"no_calls"}"""),
        )

        assertTrue(uploads.post("calllog", "{}") is UploadOutcome.Success)
        assertEquals(1, learned)

        assertTrue(
            "a gateway's own success envelope no longer deletes anything",
            uploads.post("calllog", "{}") is UploadOutcome.Retry,
        )
        assertTrue(
            "nor does a gateway's own refusal dead-letter anything",
            uploads.post("calllog", "{}") is UploadOutcome.Retry,
        )
        assertEquals(UploadOutcome.Rejected(400), uploads.post("calllog", "{}"))
        assertEquals("learned once, not on every reply", 1, learned)
    }

    @Test
    fun `a gateway response body is excluded from the operator diagnostic`() = runTest {
        server.enqueue(MockResponse(body = "<html>private document title</html>"))

        val retry = OdooClient().uploads(settings()).post("document", "{}") as UploadOutcome.Retry

        assertTrue(retry.reason.contains("200"))
        assertFalse(retry.reason.contains("private document title"))
    }

    @Test
    fun `even the first upload keeps rows when the reply identifies a different service`() =
        runTest {
            server.enqueue(MockResponse(body = """{"service":"gateway","status":"success"}"""))
            val outcome = OdooClient().uploads(settings()).post("location", "{}")
            assertTrue(outcome is UploadOutcome.Retry)
        }

    @Test
    fun `an older server that does not name itself is still believed`() = runTest {
        var learned = 0
        server.enqueue(MockResponse(body = """{"status":"success","accepted":1}"""))

        val outcome = OdooClient(onServerNamedItself = {
            learned++
        }).uploads(settings()).post("calllog", "{}")

        assertTrue(outcome is UploadOutcome.Success)
        assertEquals(0, learned)
    }

    private suspend fun linkDownFor(response: MockResponse): Boolean {
        server.enqueue(response)
        return (
            OdooClient().uploads(
                settings()
            ).post("calllog", "{}") as UploadOutcome.Retry
            ).scope == RetryScope.LINK
    }

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
        val refused = OdooClient().uploads(settings().copy(baseUrl = "http://127.0.0.1:1")).post(
            "calllog",
            "{}",
        ) as UploadOutcome.Retry
        assertTrue(refused.reason, refused.scope == RetryScope.LINK)

        server.enqueue(
            MockResponse.Builder()
                .body("""{"status":"success"}""")
                .headersDelay(2, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
        )
        val impatient = OkHttpClient.Builder()
            .readTimeout(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        val timedOut = OdooClient(impatient).uploads(settings()).post("calllog", "{}")
            as UploadOutcome.Retry
        assertFalse(timedOut.reason, timedOut.scope == RetryScope.LINK)
    }

    @Test
    fun `a success whose counters are mistyped is still a delivery`() = runTest {
        listOf("""{"n":2}""", "\"2\"", "2.0").forEach { accepted ->
            server.enqueue(
                MockResponse(
                    body = """{"status":"success","service":"remote_mobile",""" +
                        """"accepted":$accepted}""",
                ),
            )

            val outcome = OdooClient().uploads(settings()).post("calllog", "{}")

            assertTrue("$accepted: $outcome", outcome is UploadOutcome.Success)
        }
    }

    @Test
    fun `a hint the server mistyped is ignored, not fatal`() = runTest {
        server.enqueue(
            MockResponse(
                body = """{"status":"success","accepted":1,"max_payload_bytes":[1],""" +
                    """"skipped_indexes":[{"i":0}]}""",
            ),
        )

        val outcome = OdooClient().uploads(settings()).post("calllog", "{}")

        assertEquals(UploadOutcome.Success(1, 0, 0), outcome)
    }

    @Test
    fun `a lookup whose reply is mistyped is unavailable, not a parser failure`() = runTest {
        server.enqueue(MockResponse(body = """{"service":{"name":"remote_mobile"}}"""))
        server.enqueue(MockResponse(body = """{"service":"remote_mobile","vapid_public_key":{}}"""))

        val softphone = OdooClient().softphone(settings()) as SoftphoneLookup.Unavailable
        val push = OdooClient().pushKey(settings()) as PushKeyLookup.Unavailable

        assertTrue(softphone.reason, softphone.reason.contains("not the Odoo endpoint"))
        assertFalse(push.reason, push.reason.contains("JsonPrimitive"))
    }
}

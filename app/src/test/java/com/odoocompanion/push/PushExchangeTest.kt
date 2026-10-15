package com.odoocompanion.push

import android.app.Application
import com.odoocompanion.config.Settings
import com.odoocompanion.net.OdooClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
class PushExchangeTest {
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
        token = "device-token",
    )

    private fun reply(code: Int = 200, body: String) =
        server.enqueue(MockResponse(code = code, body = body))

    private fun keyReply(registered: Boolean) = reply(
        body = """{"service":"remote_mobile","status":"success",""" +
            """"vapid_public_key":"BKey","registered":$registered}""",
    )

    @Test
    fun `the key comes from the device's push route behind its token`() = runTest {
        keyReply(registered = false)

        val lookup = OdooClient().pushKey(settings())

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/remote/mobile/phone-01/push", request.target)
        assertEquals("Bearer device-token", request.headers["Authorization"])
        assertEquals(PushKeyLookup.Found("BKey", registered = false), lookup)
    }

    @Test
    fun `a registration carries the endpoint and both keys`() = runTest {
        keyReply(registered = true)

        val lookup = OdooClient().registerPush(
            settings(),
            PushRegistration("https://push.example/up/1", "BP256", "AUTH"),
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val sent = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("https://push.example/up/1", sent.getValue("endpoint").jsonPrimitive.content)
        val keys = sent.getValue("keys").jsonObject
        assertEquals("BP256", keys.getValue("p256dh").jsonPrimitive.content)
        assertEquals("AUTH", keys.getValue("auth").jsonPrimitive.content)
        assertEquals(PushKeyLookup.Found("BKey", registered = true), lookup)
    }

    @Test
    fun `taking the handset off wake-ups sends an empty endpoint`() = runTest {
        keyReply(registered = false)

        OdooClient().registerPush(settings(), null)

        val sent = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals(JsonNull, sent.getValue("endpoint"))
    }

    @Test
    fun `an odoo that sends no push says so`() = runTest {
        reply(503, """{"service":"remote_mobile","error":"push_unavailable","message":"x"}""")

        assertEquals(PushKeyLookup.Off, OdooClient().pushKey(settings()))
    }

    @Test
    fun `an answer from anything but odoo changes nothing`() = runTest {
        reply(503, "<html>Service Unavailable</html>")

        assertTrue(OdooClient().pushKey(settings()) is PushKeyLookup.Unavailable)
    }

    @Test
    fun `an odoo without the push route changes nothing`() = runTest {
        reply(404, """{"service":"remote_mobile","error":"not_found"}""")

        assertTrue(OdooClient().pushKey(settings()) is PushKeyLookup.Unavailable)
    }
}

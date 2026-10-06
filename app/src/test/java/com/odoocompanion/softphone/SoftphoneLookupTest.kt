package com.odoocompanion.softphone

import android.app.Application
import com.odoocompanion.config.Settings
import com.odoocompanion.net.OdooClient
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
class SoftphoneLookupTest {
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

    @Test
    fun `the settings come from the device's softphone route behind its token`() = runTest {
        server.enqueue(
            MockResponse(
                body = """{"service":"remote_mobile","status":"success",""" +
                    """"sip":{"username":"201","secret":"s","domain":"pbx.example"}}""",
            ),
        )

        val lookup = OdooClient().softphone(settings())

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/remote/mobile/phone-01/softphone", request.target)
        assertEquals("Bearer device-token", request.headers["Authorization"])
        assertEquals(SoftphoneLookup.Found(SoftphoneSettings("201", "s", "pbx.example")), lookup)
    }

    @Test
    fun `odoo saying the user has no extension is an answer`() = runTest {
        server.enqueue(
            MockResponse(
                code = 404,
                body = """{"service":"remote_mobile","error":"no_extension","message":"x"}""",
            ),
        )

        assertEquals(SoftphoneLookup.NoExtension, OdooClient().softphone(settings()))
    }

    @Test
    fun `a 404 from anything but odoo changes nothing`() = runTest {
        server.enqueue(MockResponse(code = 404, body = "<html>Not Found</html>"))

        assertTrue(OdooClient().softphone(settings()) is SoftphoneLookup.Unavailable)
    }

    @Test
    fun `an odoo without the softphone route changes nothing`() = runTest {
        server.enqueue(
            MockResponse(code = 404, body = """{"service":"remote_mobile","error":"not_found"}""")
        )

        assertTrue(OdooClient().softphone(settings()) is SoftphoneLookup.Unavailable)
    }

    @Test
    fun `odoo refusing the device token is an answer that stops the softphone`() = runTest {
        for (code in listOf(401, 403)) {
            server.enqueue(
                MockResponse(
                    code = code,
                    body = """{"service":"remote_mobile","error":"authentication_failed",""" +
                        """"message":"unauthenticated request"}""",
                ),
            )

            assertEquals(SoftphoneLookup.Refused(code), OdooClient().softphone(settings()))
        }
    }

    @Test
    fun `a 401 from anything but odoo changes nothing`() = runTest {
        server.enqueue(MockResponse(code = 401, body = "<html>Sign in</html>"))

        assertTrue(OdooClient().softphone(settings()) is SoftphoneLookup.Unavailable)
    }
}

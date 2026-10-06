package com.odoocompanion.softphone

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SoftphoneSettingsTest {
    private fun reply(sip: String) =
        Json.parseToJsonElement("""{"service":"remote_mobile","sip":$sip}""").jsonObject

    @Test
    fun `a reply names the extension, its secret and the phone system`() {
        val settings = SoftphoneSettings.fromReply(
            reply(
                """{"username":"201","secret":"s3cret","domain":"erp.agromarin.mx","transport":"tls"}"""
            ),
        )

        assertEquals(SoftphoneSettings("201", "s3cret", "erp.agromarin.mx"), settings)
        assertEquals("sip:201@erp.agromarin.mx", settings!!.identity)
        assertEquals("sip:erp.agromarin.mx:5061;transport=tls", settings.serverAddress)
    }

    @Test
    fun `a reply missing any part, or offering anything but tls, is not used`() {
        assertNull(
            SoftphoneSettings.fromReply(reply("""{"username":"201","secret":"","domain":"x"}""")),
        )
        assertNull(
            SoftphoneSettings.fromReply(reply("""{"username":"201","secret":"s","domain":""}"""))
        )
        assertNull(
            SoftphoneSettings.fromReply(
                reply("""{"username":"201","secret":"s","domain":"x","transport":"udp"}"""),
            ),
        )
        assertNull(SoftphoneSettings.fromReply(reply("[]")))
        assertNull(SoftphoneSettings.fromReply(null))
    }

    @Test
    fun `the secret never reaches a log`() {
        assertFalse("s3cret" in SoftphoneSettings("201", "s3cret", "x").toString())
    }

    @Test
    fun `the domain is a bare host, since a port and a scheme are added to it`() {
        fun domain(value: String) = SoftphoneSettings.fromReply(
            reply("""{"username":"201","secret":"s","domain":"$value"}"""),
        )?.domain

        assertEquals("pbx.example", domain("pbx.example"))
        assertEquals("10.0.0.5", domain("10.0.0.5"))
        assertNull(domain("sip:pbx.example"))
        assertNull(domain("pbx.example:5061"))
        assertNull(domain("https://pbx.example"))
        assertNull(domain("pbx.example/ws"))
        assertNull(domain("201@pbx.example"))
        assertNull(domain("pbx example"))
        assertNull(domain("-pbx.example"))
    }

    @Test
    fun `a username that would break the SIP address is not used`() {
        fun username(value: String) = SoftphoneSettings.fromReply(
            reply("""{"username":"$value","secret":"s","domain":"pbx.example"}"""),
        )?.username

        assertEquals("201", username("201"))
        assertEquals("ventas.mx-1", username("ventas.mx-1"))
        assertNull(username("201@evil.example"))
        assertNull(username("201;transport=udp"))
        assertNull(username("2 01"))
    }
}

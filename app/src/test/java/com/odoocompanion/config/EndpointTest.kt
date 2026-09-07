package com.odoocompanion.config

import android.app.Application
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class EndpointTest {
    private fun settings(identifier: String, baseUrl: String = "https://odoo.example.com") =
        Settings(baseUrl = baseUrl, identifier = identifier, token = "t")

    @Test
    fun `a normal identifier builds the documented route`() {
        assertEquals(
            "https://odoo.example.com/remote/mobile/PHONE-001/location",
            settings("PHONE-001").endpoint("location"),
        )
    }

    @Test
    fun `a trailing slash on the base url does not double up`() {
        assertEquals(
            "https://odoo.example.com/remote/mobile/p1/calllog",
            settings("p1", "https://odoo.example.com/").endpoint("calllog"),
        )
    }

    @Test
    fun `a base url with its own path prefix keeps that prefix`() {
        assertEquals(
            "https://host.example.com/odoo/remote/mobile/p1/location",
            settings("p1", "https://host.example.com/odoo").endpoint("location"),
        )
    }

    @Test
    fun `an identifier cannot walk out of the route`() {
        assertEquals(
            "https://odoo.example.com/remote/mobile/" +
                "..%2F..%2Fweb%2Fsession%2Fauthenticate/location",
            settings("../../web/session/authenticate").endpoint("location"),
        )
    }

    @Test
    fun `a fragment or query in the identifier cannot truncate the path`() {
        assertTrue(settings("a#f").endpoint("location").endsWith("/location"))
        assertTrue(settings("a?x=1").endpoint("location").endsWith("/location"))
    }

    @Test
    fun `an unusable base url throws so the client can retry rather than crash`() {
        val thrown = runCatching { settings("p1", "odoo.example.com").endpoint("location") }
        assertTrue(thrown.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `identifiers that survive a url path unchanged are accepted`() {
        listOf("PHONE-001", "phone_01", "a.b~c", "0").forEach {
            assertTrue(it, isUsableIdentifier(it))
        }
    }

    @Test
    fun `identifiers that would rewrite the request are refused`() {
        listOf("", "  ", "../admin", "a/b", "a#f", "a?x=1", "phone 01", "a%2Fb").forEach {
            assertFalse(it, isUsableIdentifier(it))
        }
    }

    @Test
    fun `a managed push with an unusable identifier leaves the stored one alone`() {
        val bundle = Bundle().apply {
            putString("base_url", "https://odoo.example.com")
            putString("identifier", "../../web")
        }

        val values = ManagedConfig.fromBundle(bundle)

        assertNull(values.identifier)
        assertEquals("https://odoo.example.com", values.baseUrl)
    }
}

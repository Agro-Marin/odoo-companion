package com.odoocompanion.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class LocalNetworkTest {
    private fun resolvesTo(vararg addresses: String): (String) -> List<InetAddress> =
        { _ -> addresses.map(InetAddress::getByName) }

    private val unresolvable: (String) -> List<InetAddress> = { throw UnknownHostException(it) }

    @Test
    fun `the private ranges, link-local and unique-local addresses are local`() {
        listOf(
            "10.0.2.2",
            "172.16.0.1",
            "172.31.255.254",
            "192.168.1.20",
            "169.254.10.1",
            "fe80::1",
            "[fd12:3456::1]",
        ).forEach { assertTrue(it, LocalNetwork.isLocal(it, unresolvable)) }
    }

    @Test
    fun `public addresses, loopback and the ranges beside the private ones are not`() {
        listOf("8.8.8.8", "172.32.0.1", "192.169.0.1", "127.0.0.1", "::1", "2001:db8::1")
            .forEach { assertFalse(it, LocalNetwork.isLocal(it, unresolvable)) }
    }

    @Test
    fun `a name is as local as what it resolves to`() {
        assertTrue(LocalNetwork.isLocal("odoo.empresa.lan", resolvesTo("192.168.10.5")))
        assertFalse(LocalNetwork.isLocal("odoo.example.com", resolvesTo("93.184.216.34")))
        assertTrue(
            "one local answer is enough to need the permission",
            LocalNetwork.isLocal("odoo.example.com", resolvesTo("93.184.216.34", "10.1.1.1")),
        )
    }

    @Test
    fun `an mdns name is local without a lookup, an unresolvable one is not`() {
        assertTrue(LocalNetwork.isLocal("odoo.local.", unresolvable))
        assertFalse(LocalNetwork.isLocal("odoo.example.com", unresolvable))
    }

    @Test
    fun `odoo or the phone system on the lan is enough`() {
        assertTrue(LocalNetwork.anyLocal("http://192.168.1.20:8069", null, unresolvable))
        assertTrue(LocalNetwork.anyLocal("https://odoo.example.com", "10.0.0.5", unresolvable))
        assertFalse(LocalNetwork.anyLocal("https://8.8.4.4", null, unresolvable))
        assertFalse("not enrolled, nothing to reach", LocalNetwork.anyLocal("", null, unresolvable))
        assertFalse(LocalNetwork.anyLocal(null, null, unresolvable))
    }
}

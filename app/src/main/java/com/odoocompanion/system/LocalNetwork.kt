package com.odoocompanion.system

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

// From Android 17 an app that targets it reaches a local-network address only
// with ACCESS_LOCAL_NETWORK, a runtime permission; without it a connection to
// an Odoo or a phone system on the company LAN simply times out, and the queue
// grows with nothing on the screen to say why. Which servers are local decides
// whether the permission is asked for at all: a handset talking to a cloud Odoo
// is never shown a "nearby devices" prompt.
object LocalNetwork {
    // The servers this handset is configured to reach: Odoo, and the phone
    // system once Odoo has given it a line.
    fun anyLocal(
        baseUrl: String?,
        softphoneDomain: String?,
        resolve: (String) -> List<InetAddress> = ::lookup,
    ): Boolean = listOfNotNull(baseUrl?.toHttpUrlOrNull()?.host, softphoneDomain)
        .any { isLocal(it, resolve) }

    // An address literal is read without a lookup. A name is resolved, because a
    // company's internal name (odoo.empresa.lan) points at a LAN address as
    // often as not; a name that does not resolve says nothing, so it is not
    // taken as local.
    fun isLocal(host: String, resolve: (String) -> List<InetAddress> = ::lookup): Boolean {
        val name = host.trim().trimEnd('.').removeSurrounding("[", "]").lowercase()
        if (name.isEmpty()) return false
        if (name.endsWith(".local")) return true
        val addresses = try {
            if (isLiteral(name)) listOf(InetAddress.getByName(name)) else resolve(name)
        } catch (_: UnknownHostException) {
            return false
        }
        return addresses.any(::isLocalAddress)
    }

    // getByName reads a literal without a lookup; anything else goes to DNS.
    private fun isLiteral(name: String): Boolean = ':' in name || IPV4.matches(name)

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    // The private ranges (10/8, 172.16/12, 192.168/16), link-local and IPv6
    // unique-local (fc00::/7). Loopback is not: the platform exempts it.
    fun isLocalAddress(address: InetAddress): Boolean = when {
        address.isLoopbackAddress -> false
        address.isSiteLocalAddress || address.isLinkLocalAddress -> true
        address is Inet6Address -> address.address[0].toInt() and 0xfe == 0xfc
        else -> false
    }

    private fun lookup(name: String): List<InetAddress> = InetAddress.getAllByName(name).toList()
}

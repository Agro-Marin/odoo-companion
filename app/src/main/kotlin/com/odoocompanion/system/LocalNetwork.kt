package com.odoocompanion.system

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

object LocalNetwork {
    fun anyLocal(
        baseUrl: String?,
        softphoneDomain: String?,
        resolve: (String) -> List<InetAddress> = ::lookup,
    ): Boolean = listOfNotNull(baseUrl?.toHttpUrlOrNull()?.host, softphoneDomain)
        .any { isLocal(it, resolve) }

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

    private fun isLiteral(name: String): Boolean = ':' in name || IPV4.matches(name)

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    fun isLocalAddress(address: InetAddress): Boolean = when {
        address.isLoopbackAddress -> false
        address.isSiteLocalAddress || address.isLinkLocalAddress -> true
        address is Inet6Address -> address.address[0].toInt() and 0xfe == 0xfc
        else -> false
    }

    private fun lookup(name: String): List<InetAddress> = InetAddress.getAllByName(name).toList()
}

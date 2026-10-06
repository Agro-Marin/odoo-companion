package com.odoocompanion.softphone

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The extension this handset's softphone registers on the company phone system:
 * its user's, as Odoo's device_voip hands it out behind the device token.
 */
data class SoftphoneSettings(val username: String, val secret: String, val domain: String,) {
    val identity: String get() = "sip:$username@$domain"

    // SIP over TLS on the phone system's own port; the browser softphone is the
    // one that comes in through the website's websocket instead
    val serverAddress: String get() = "sip:$domain:$TLS_PORT;transport=tls"

    override fun toString(): String =
        "SoftphoneSettings(username=$username, secret=***, domain=$domain)"

    companion object {
        const val TLS_PORT = 5061

        /** The `sip` object of a softphone reply, or null when it is unusable. */
        fun fromReply(body: JsonObject?): SoftphoneSettings? {
            val sip = runCatching { body?.get("sip")?.jsonObject }.getOrNull() ?: return null
            fun field(name: String) = runCatching {
                sip[name]?.jsonPrimitive?.contentOrNull
            }.getOrNull()?.trim().orEmpty()
            val settings = SoftphoneSettings(field("username"), field("secret"), field("domain"))
            val transport = field("transport").ifEmpty { "tls" }
            return settings.takeIf {
                transport == "tls" &&
                    USERNAME.matches(it.username) &&
                    it.secret.isNotEmpty() &&
                    HOST.matches(it.domain)
            }
        }

        // The domain is spliced into SIP URIs with the port appended, so a scheme,
        // a port, a path or a user part in it would address something else: a
        // host name or an IPv4 address only.
        private val HOST = Regex(
            """(?i)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*""",
        )

        // what may stand before the @ unescaped: no delimiter of the URI itself
        private val USERNAME = Regex("""[A-Za-z0-9._~!$&'()*+,=%-]+""")
    }
}

/** What asking Odoo for the softphone settings came to. */
sealed interface SoftphoneLookup {
    data class Found(val settings: SoftphoneSettings) : SoftphoneLookup

    /** Odoo answered for this device: its user has no extension. */
    data object NoExtension : SoftphoneLookup

    /** Odoo refused the device token: revoked or rotated, so the extension goes too. */
    data class Refused(val code: Int) : SoftphoneLookup

    /** No answer worth acting on; whatever was set up stays as it is. */
    data class Unavailable(val reason: String) : SoftphoneLookup
}

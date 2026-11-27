package com.odoocompanion.softphone

import com.odoocompanion.net.objectAt
import com.odoocompanion.net.primitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

data class SoftphoneSettings(val username: String, val secret: String, val domain: String,) {
    val identity: String get() = "sip:$username@$domain"

    val serverAddress: String get() = "sip:$domain:$TLS_PORT;transport=tls"

    override fun toString(): String =
        "SoftphoneSettings(username=$username, secret=***, domain=$domain)"

    companion object {
        const val TLS_PORT = 5061

        fun fromReply(body: JsonObject?): SoftphoneSettings? {
            val sip = body.objectAt("sip") ?: return null
            fun field(name: String) = sip.primitive(name)?.contentOrNull?.trim().orEmpty()
            val settings = SoftphoneSettings(field("username"), field("secret"), field("domain"))
            val transport = field("transport").ifEmpty { "tls" }
            return settings.takeIf {
                transport == "tls" &&
                    USERNAME.matches(it.username) &&
                    it.secret.isNotEmpty() &&
                    HOST.matches(it.domain)
            }
        }

        private val HOST = Regex(
            """(?i)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*""",
        )

        private val USERNAME = Regex("""[A-Za-z0-9._~!$&'()*+,=%-]+""")
    }
}

sealed interface SoftphoneLookup {
    data class Found(val settings: SoftphoneSettings) : SoftphoneLookup

    data object NoExtension : SoftphoneLookup

    data class Refused(val code: Int) : SoftphoneLookup

    data class Unavailable(val reason: String) : SoftphoneLookup
}

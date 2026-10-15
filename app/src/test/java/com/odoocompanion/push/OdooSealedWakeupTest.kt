package com.odoocompanion.push

import com.google.crypto.tink.apps.fixed_webpush.WebPushHybridDecrypt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import java.util.Base64

/**
 * A wake-up as Odoo seals it -- mail's web_push._encrypt_payload, RFC 8291
 * aes128gcm with a 4096-octet record -- opened by the Tink decrypter the
 * UnifiedPush connector uses. The fixture was produced by the server code, so
 * this pins the two implementations to each other, not each to itself.
 */
class OdooSealedWakeupTest {
    private val fixture = Json.parseToJsonElement(
        checkNotNull(javaClass.getResource("/push/odoo_sealed_wakeup.json")).readText(),
    ).jsonObject

    private fun field(name: String) = fixture.getValue(name).jsonPrimitive.content

    private fun urlBytes(name: String): ByteArray = Base64.getUrlDecoder().decode(field(name))

    private fun decrypter(auth: ByteArray = urlBytes("auth")) = WebPushHybridDecrypt.Builder()
        .withAuthSecret(auth)
        .withRecipientPublicKey(urlBytes("public_key"))
        .withRecipientPrivateKey(urlBytes("private_key"))
        .build()

    private val sealed = Base64.getDecoder().decode(field("sealed"))

    @Test
    fun `the connector opens what odoo sealed and reads its command`() {
        val opened = decrypter().decrypt(sealed, null)

        assertEquals(field("plain"), opened.decodeToString())
        assertEquals(PushCommand.SYNC, PushCommand.parse(opened))
    }

    @Test
    fun `a wake-up sealed to another auth secret does not open`() {
        assertThrows(GeneralSecurityException::class.java) {
            decrypter(auth = ByteArray(16)).decrypt(sealed, null)
        }
    }
}

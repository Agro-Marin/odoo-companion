package com.odoocompanion.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class AdminGateTest {
    private var now = 0L

    private fun gate(password: String?) = AdminGate(
        expectedSha256 = password?.let { sha256("salt:$it") }.orEmpty(),
        salt = "salt",
        clock = { now },
    )

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

    @Test
    fun `a build without a password leaves Settings open`() {
        val gate = gate(null)

        assertFalse(gate.required)
        assertTrue(gate.isOpen())
    }

    @Test
    fun `only the right password opens Settings`() {
        val gate = gate("Xiuman-1234")

        assertFalse(gate.isOpen())
        assertFalse(gate.unlock("xiuman-1234"))
        assertFalse(gate.isOpen())
        assertTrue(gate.unlock("Xiuman-1234"))
        assertTrue(gate.isOpen())
    }

    @Test
    fun `an unlock lapses after a few minutes`() {
        val gate = gate("pw")
        gate.unlock("pw")

        now += AdminGate.UNLOCKED_MILLIS - 1
        assertTrue(gate.isOpen())
        now += 1
        assertFalse(gate.isOpen())
    }
}

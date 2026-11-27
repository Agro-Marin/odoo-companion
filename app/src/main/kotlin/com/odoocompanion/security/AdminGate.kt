package com.odoocompanion.security

import android.os.SystemClock
import com.odoocompanion.BuildConfig
import java.security.MessageDigest

class AdminGate(
    private val expectedSha256: String = BuildConfig.ADMIN_PASSWORD_SHA256,
    private val salt: String = BuildConfig.ADMIN_PASSWORD_SALT,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private var unlockedAt: Long? = null

    val required: Boolean get() = expectedSha256.isNotEmpty()

    fun isOpen(): Boolean {
        if (!required) return true
        val at = unlockedAt ?: return false
        return clock() - at < UNLOCKED_MILLIS
    }

    fun unlock(password: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$salt:$password".toByteArray())
        val expected = expectedSha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val matches = MessageDigest.isEqual(digest, expected)
        if (matches) unlockedAt = clock()
        return matches
    }

    companion object {
        const val UNLOCKED_MILLIS = 5L * 60 * 1000
    }
}

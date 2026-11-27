package com.odoocompanion.system

internal class StaleLookup(private val retryEvery: Long = RETRY_MILLIS) {
    private var failing = false
    private var askedAt: Long? = null

    @Synchronized
    fun failed() {
        failing = true
    }

    @Synchronized
    fun settled() {
        failing = false
        askedAt = null
    }

    @Synchronized
    fun dueAfterDelivery(now: Long): Boolean {
        if (!failing) return false
        val last = askedAt
        if (last != null && now - last in 0..<retryEvery) return false
        askedAt = now
        return true
    }

    companion object {
        const val RETRY_MILLIS = 15L * 60 * 1000
    }
}

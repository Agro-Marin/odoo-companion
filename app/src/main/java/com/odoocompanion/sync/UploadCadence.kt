package com.odoocompanion.sync

internal object UploadCadence {
    const val BATCH_THRESHOLD = 20

    fun dueNow(queued: Int, oldestCreatedAt: Long?, windowSeconds: Long, now: Long): Boolean {
        if (queued <= 0) return false
        if (queued >= BATCH_THRESHOLD) return true

        val oldest = oldestCreatedAt ?: return false

        return now - oldest >= windowSeconds * 1_000 || now < oldest
    }
}

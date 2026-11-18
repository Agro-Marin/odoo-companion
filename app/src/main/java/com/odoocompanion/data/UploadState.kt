package com.odoocompanion.data

enum class UploadState {
    WAITING,
    QUEUED,
    DELIVERED,
    SET_ASIDE,
    GONE,
    WITHDRAWN,
    UNCONFIRMED;

    companion object {
        fun fromDeadReason(reason: String?): UploadState = when (reason) {
            null -> QUEUED
            DeadReason.ACKNOWLEDGED, DeadReason.KEPT_ON_DISK -> DELIVERED
            DeadReason.GONE -> GONE
            DeadReason.WITHDRAWN -> WITHDRAWN
            else -> SET_ASIDE
        }

        fun forFile(row: QueuedFileState?, saved: UploadState?): UploadState =
            row?.let { fromDeadReason(it.deadReason) } ?: saved ?: UNCONFIRMED
    }
}

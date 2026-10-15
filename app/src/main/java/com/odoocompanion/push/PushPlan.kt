package com.odoocompanion.push

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What a wake-up from Odoo asks the handset to do. */
enum class PushCommand(val wire: String) {
    SYNC("sync"),
    SOFTPHONE("softphone"),
    ;

    companion object {
        fun parse(content: ByteArray): PushCommand? {
            val command = runCatching {
                Json.parseToJsonElement(content.decodeToString())
                    .jsonObject["command"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            return entries.firstOrNull { it.wire == command }
        }
    }
}

/** Where a distributor delivers this handset's wake-ups, and the keys they are sealed to. */
data class PushRegistration(val endpoint: String, val p256dh: String, val auth: String) {
    override fun toString(): String = "PushRegistration(endpoint=$endpoint)"
}

/** What asking Odoo for its push key came to. */
sealed interface PushKeyLookup {
    data class Found(val vapidPublicKey: String, val registered: Boolean) : PushKeyLookup

    /** Odoo answered for itself: it sends no push messages. */
    data object Off : PushKeyLookup

    /** No answer worth acting on; whatever was registered stays as it is. */
    data class Unavailable(val reason: String) : PushKeyLookup
}

/** Whether this handset takes wake-ups, and through which distributor. */
sealed interface PushPlan {
    data class Register(val distributor: String, val vapidPublicKey: String) : PushPlan

    data object Unregister : PushPlan

    data object Keep : PushPlan

    data object NoDistributor : PushPlan

    companion object {
        /**
         * Wake-ups only ever shorten a wait the periodic work already bounds, so
         * nothing here may stop a handset from reporting: an Odoo out of reach
         * keeps the registration as it is. The distributor the user or the policy
         * chose is kept while installed; a handset without one takes the first
         * installed, as a managed phone has nobody to pick for it.
         */
        fun decide(
            enrolled: Boolean,
            lookup: PushKeyLookup,
            saved: String?,
            installed: List<String>,
        ): PushPlan = when {
            !enrolled -> Unregister

            lookup is PushKeyLookup.Off -> Unregister

            lookup !is PushKeyLookup.Found -> Keep

            else -> (saved?.takeIf { it in installed } ?: installed.minOrNull())
                ?.let { Register(it, lookup.vapidPublicKey) }
                ?: NoDistributor
        }
    }
}

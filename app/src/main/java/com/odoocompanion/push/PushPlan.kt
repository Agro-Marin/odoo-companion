package com.odoocompanion.push

import com.odoocompanion.net.asJsonObject
import com.odoocompanion.net.primitive
import kotlinx.serialization.json.contentOrNull

enum class PushCommand(val wire: String) {
    SYNC("sync"),
    SOFTPHONE("softphone"),
    ;

    companion object {
        fun parse(content: ByteArray): PushCommand? {
            val command = content.decodeToString().asJsonObject()
                .primitive("command")?.contentOrNull
            return entries.firstOrNull { it.wire == command }
        }
    }
}

data class PushRegistration(val endpoint: String, val p256dh: String, val auth: String) {
    override fun toString(): String = "PushRegistration(endpoint=$endpoint)"
}

sealed interface PushKeyLookup {
    data class Found(val vapidPublicKey: String, val registered: Boolean) : PushKeyLookup

    data object Off : PushKeyLookup

    data class Unavailable(val reason: String) : PushKeyLookup
}

sealed interface PushPlan {
    data class Register(val distributor: String, val vapidPublicKey: String) : PushPlan

    data object Unregister : PushPlan

    data object Keep : PushPlan

    data object NoDistributor : PushPlan

    companion object {
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

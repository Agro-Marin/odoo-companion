package com.odoocompanion.update

import com.odoocompanion.net.WireJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

@Serializable
data class UpdateManifest(
    @SerialName("version_code") val versionCode: Long,
    @SerialName("version_name") val versionName: String,
    val apk: String,
    val sha256: String,
    val size: Long,
)

object UpdateFeed {
    private const val RELEASES = "https://github.com/Agro-Marin/odoo-companion/releases/"

    const val LATEST = RELEASES + "latest/download/"

    const val MANIFEST = "update.json"

    private val SHA256 = Regex("[0-9a-f]{64}")

    private val APK_NAME = Regex("[A-Za-z0-9._-]+\\.apk")

    private val VERSION_NAME = Regex("[A-Za-z0-9._-]+")

    fun parse(body: String): UpdateManifest? {
        val manifest = try {
            WireJson.decodeFromString(UpdateManifest.serializer(), body)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        return manifest.takeIf {
            it.versionCode > 0 &&
                it.size > 0 &&
                SHA256.matches(it.sha256) &&
                APK_NAME.matches(it.apk) &&
                ".." !in it.apk &&
                VERSION_NAME.matches(it.versionName) &&
                ".." !in it.versionName
        }
    }

    fun isNewer(manifest: UpdateManifest, installedVersionCode: Long): Boolean =
        manifest.versionCode > installedVersionCode

    fun apkUrl(manifest: UpdateManifest): String =
        "${RELEASES}download/v${manifest.versionName}/${manifest.apk}"
}

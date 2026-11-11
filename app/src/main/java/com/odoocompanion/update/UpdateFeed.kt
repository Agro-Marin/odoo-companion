package com.odoocompanion.update

import com.odoocompanion.net.WireJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

// What a release publishes beside its APK, as tools/release.sh writes it: the
// build it carries, the APK's file name among the release's assets, and its
// digest, so a download cut short or swapped on the way is never installed.
@Serializable
data class UpdateManifest(
    @SerialName("version_code") val versionCode: Long,
    @SerialName("version_name") val versionName: String,
    val apk: String,
    val sha256: String,
    val size: Long,
)

// Where updates come from: the latest GitHub release of this app's own
// repository. "releases/latest/download/<asset>" redirects to the newest
// release that is not a draft or a pre-release, so a release is offered to
// the fleet the moment it is published, and a pre-release never is.
object UpdateFeed {
    const val LATEST = "https://github.com/Agro-Marin/odoo-companion/releases/latest/download/"

    const val MANIFEST = "update.json"

    private val SHA256 = Regex("[0-9a-f]{64}")

    // A bare file name ending in .apk: anything else could point the download
    // outside the release it was announced in.
    private val APK_NAME = Regex("[A-Za-z0-9._-]+\\.apk")

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
                ".." !in it.apk
        }
    }

    fun isNewer(manifest: UpdateManifest, installedVersionCode: Long): Boolean =
        manifest.versionCode > installedVersionCode

    fun apkUrl(manifest: UpdateManifest): String = LATEST + manifest.apk
}

package com.odoocompanion.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import com.odoocompanion.net.WireJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed interface UpdateCheck {
    data class Available(val manifest: UpdateManifest) : UpdateCheck

    data object Current : UpdateCheck

    data class Failed(val reason: String) : UpdateCheck
}

sealed interface InstallStart {
    // Android asks once whether this app may install apps; the person grants
    // it on a settings screen and taps Update again.
    data object NeedsPermission : InstallStart

    data object Started : InstallStart

    data class Failed(val reason: String) : InstallStart
}

// Finds, downloads and hands to Android a newer build published as a GitHub
// release. Android shows its own confirmation before installing -- always the
// first time, and again on a handset where this app is not the installer of
// record -- and refuses an APK not signed with the key of the installed one.
class Updater(context: Context) {
    private val context = context.applicationContext

    private val http = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val installedVersionCode: Long
        get() = PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(context.packageName, 0),
        )

    // The newer build the last check found, kept so Home offers it again
    // without asking GitHub on every visit.
    fun known(): UpdateManifest? = prefs.getString(KEY_AVAILABLE, null)
        ?.let(UpdateFeed::parse)
        ?.takeIf { UpdateFeed.isNewer(it, installedVersionCode) }

    fun dueForCheck(now: Long = System.currentTimeMillis()): Boolean =
        now - prefs.getLong(KEY_CHECKED_AT, 0) >= CHECK_EVERY_MILLIS

    suspend fun check(): UpdateCheck = withContext(Dispatchers.IO) {
        val body = try {
            fetch(UpdateFeed.LATEST + UpdateFeed.MANIFEST).use { response ->
                if (!response.isSuccessful) {
                    return@withContext UpdateCheck.Failed("HTTP ${response.code}")
                }
                response.body.string()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not reach the update feed", e)
            return@withContext UpdateCheck.Failed(e.message ?: e.javaClass.simpleName)
        }
        val manifest = UpdateFeed.parse(body)
            ?: return@withContext UpdateCheck.Failed("unreadable update manifest")
        val newer = UpdateFeed.isNewer(manifest, installedVersionCode)
        prefs.edit {
            putLong(KEY_CHECKED_AT, System.currentTimeMillis())
            if (newer) {
                putString(
                    KEY_AVAILABLE,
                    WireJson.encodeToString(UpdateManifest.serializer(), manifest),
                )
            } else {
                remove(KEY_AVAILABLE)
            }
        }
        if (newer) UpdateCheck.Available(manifest) else UpdateCheck.Current
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    suspend fun install(manifest: UpdateManifest): InstallStart {
        if (!canInstall()) return InstallStart.NeedsPermission
        return withContext(Dispatchers.IO) {
            val apk = try {
                download(manifest)
            } catch (e: IOException) {
                Log.w(TAG, "Update ${manifest.versionName} could not be downloaded", e)
                return@withContext InstallStart.Failed(e.message ?: e.javaClass.simpleName)
            }
            try {
                hand(apk)
                InstallStart.Started
            } catch (e: IOException) {
                Log.w(TAG, "Android would not take update ${manifest.versionName}", e)
                InstallStart.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                apk.delete()
            }
        }
    }

    private fun fetch(url: String) = http.newCall(Request.Builder().url(url).build()).execute()

    // Into the app's cache, checked against the manifest's size and digest
    // before Android ever sees it.
    private fun download(manifest: UpdateManifest): File {
        val folder = File(context.cacheDir, FOLDER).apply { mkdirs() }
        folder.listFiles()?.forEach(File::delete)
        val target = File(folder, manifest.apk)
        val digest = MessageDigest.getInstance("SHA-256")
        fetch(UpdateFeed.apkUrl(manifest)).use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (target.length() != manifest.size || actual != manifest.sha256) {
            target.delete()
            throw IOException("the download does not match the release's digest")
        }
        return target
    }

    private fun hand(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
            .apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
                // Once this app is the installer of record, Android 12+ may
                // update it without asking; the first time it always asks.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite(apk.name, 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // Mutable: the installer writes its status and, when it needs the
            // person to confirm, the intent to show them into this broadcast.
            val status = PendingIntent.getBroadcast(
                context,
                id,
                Intent(context, UpdateReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(status.intentSender)
        }
    }

    private companion object {
        const val TAG = "Updater"

        const val PREFS = "updates"
        const val KEY_CHECKED_AT = "checked_at"
        const val KEY_AVAILABLE = "available"
        const val FOLDER = "updates"

        const val CHECK_EVERY_MILLIS = 6L * 60 * 60 * 1000
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 60L
        const val BUFFER_BYTES = 64 * 1024
    }
}

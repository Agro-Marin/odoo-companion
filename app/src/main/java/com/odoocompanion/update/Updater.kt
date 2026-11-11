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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

sealed interface UpdateStep {
    data class Downloading(val percent: Int) : UpdateStep

    data object Verifying : UpdateStep

    data object Installing : UpdateStep
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

    val progress: StateFlow<UpdateStep?> get() = step

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
        step.value = UpdateStep.Downloading(0)
        return withContext(Dispatchers.IO) {
            val apk = try {
                download(manifest)
            } catch (e: IOException) {
                Log.w(TAG, "Update ${manifest.versionName} could not be downloaded", e)
                step.value = null
                return@withContext InstallStart.Failed(e.message ?: e.javaClass.simpleName)
            }
            try {
                step.value = UpdateStep.Installing
                prefs.edit { putLong(KEY_RELAUNCH, manifest.versionCode) }
                hand(apk)
                InstallStart.Started
            } catch (e: IOException) {
                Log.w(TAG, "Android would not take update ${manifest.versionName}", e)
                prefs.edit { remove(KEY_RELAUNCH) }
                step.value = null
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
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        done += read
                        step.value = UpdateStep.Downloading(percentOf(done, manifest.size))
                    }
                }
            }
        }
        step.value = UpdateStep.Verifying
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

    companion object {
        private val step = MutableStateFlow<UpdateStep?>(null)

        fun settled() {
            step.value = null
        }

        fun percentOf(done: Long, total: Long): Int =
            if (total <= 0) 0 else (done * PERCENT / total).toInt().coerceIn(0, PERCENT)

        fun takeRelaunch(context: Context): Long? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val target = prefs.getLong(KEY_RELAUNCH, 0).takeIf { it > 0 } ?: return null
            prefs.edit { remove(KEY_RELAUNCH) }
            val installed = PackageInfoCompat.getLongVersionCode(
                context.packageManager.getPackageInfo(context.packageName, 0),
            )
            return target.takeIf { installed >= it }
        }

        private const val TAG = "Updater"
        private const val KEY_RELAUNCH = "relaunch_after"
        private const val PERCENT = 100

        private const val PREFS = "updates"
        private const val KEY_CHECKED_AT = "checked_at"
        private const val KEY_AVAILABLE = "available"
        private const val FOLDER = "updates"

        private const val CHECK_EVERY_MILLIS = 6L * 60 * 60 * 1000
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 60L
        private const val BUFFER_BYTES = 64 * 1024
    }
}

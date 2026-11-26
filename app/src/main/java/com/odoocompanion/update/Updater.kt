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
import com.odoocompanion.net.readResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    data object NeedsPermission : InstallStart

    data object AlreadyInProgress : InstallStart

    data object Started : InstallStart

    data class Failed(val reason: String) : InstallStart
}

sealed interface UpdateStep {
    data class Downloading(val percent: Int) : UpdateStep

    data object Verifying : UpdateStep

    data object Installing : UpdateStep
}

class Updater(context: Context, private val http: OkHttpClient = HTTP) {
    private val context = context.applicationContext

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val progress: StateFlow<UpdateStep?> get() = step

    val installedVersionCode: Long
        get() = PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(context.packageName, 0),
        )

    fun known(): UpdateManifest? = prefs.getString(KEY_AVAILABLE, null)
        ?.let(UpdateFeed::parse)
        ?.takeIf { UpdateFeed.isNewer(it, installedVersionCode) }

    fun dueForCheck(now: Long = System.currentTimeMillis()): Boolean =
        now - prefs.getLong(KEY_CHECKED_AT, 0) >= CHECK_EVERY_MILLIS

    suspend fun check(): UpdateCheck = checkLock.withLock { checkFeed() }

    private suspend fun checkFeed(): UpdateCheck = withContext(Dispatchers.IO) {
        val body = try {
            call(UpdateFeed.LATEST + UpdateFeed.MANIFEST).readResponse { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}")
                }
                val manifest = response.peekBody(MAX_MANIFEST_BYTES + 1)
                if (manifest.contentLength() > MAX_MANIFEST_BYTES) {
                    throw IOException("oversized update manifest")
                }
                manifest.string()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not read the update feed", e)
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

    suspend fun install(manifest: UpdateManifest): InstallStart = withContext(Dispatchers.IO) {
        if (!canInstall()) return@withContext InstallStart.NeedsPermission
        try {
            if (!begin(context)) return@withContext InstallStart.AlreadyInProgress
        } catch (failure: SecurityException) {
            return@withContext failed(failure)
        }
        var apk: File? = null
        var committed = false
        try {
            val folder = File(context.cacheDir, FOLDER).apply { mkdirs() }
            folder.listFiles()?.forEach(File::delete)
            apk = File.createTempFile("release-", ".apk", folder)
            download(manifest, apk)
            step.value = UpdateStep.Installing
            hand(apk, manifest.versionCode)
            committed = true
            InstallStart.Started
        } catch (failure: IOException) {
            failed(failure)
        } catch (failure: SecurityException) {
            failed(failure)
        } catch (failure: IllegalStateException) {
            failed(failure)
        } finally {
            apk?.delete()
            if (!committed) clear(context, installed = false)
        }
    }

    private fun failed(failure: Exception): InstallStart.Failed {
        Log.w(TAG, "Update could not be installed", failure)
        return InstallStart.Failed(failure.message ?: failure.javaClass.simpleName)
    }

    private fun call(url: String) = http.newCall(Request.Builder().url(url).build())

    private fun download(manifest: UpdateManifest, target: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        call(UpdateFeed.apkUrl(manifest)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > manifest.size - done) throw IOException("oversized APK")
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
            throw IOException("the download does not match the release's digest")
        }
    }

    private fun hand(apk: File, versionCode: Long) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
            .apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
        val id = installer.createSession(params)
        var committed = false
        try {
            prefs.edit(commit = true) {
                putLong(KEY_RELAUNCH, versionCode)
                putInt(KEY_SESSION, id)
            }
            installer.openSession(id).use { session ->
                session.openWrite(apk.name, 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val status = PendingIntent.getBroadcast(
                    context,
                    id,
                    Intent(
                        context,
                        UpdateReceiver::class.java
                    ).putExtra(PackageInstaller.EXTRA_SESSION_ID, id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(status.intentSender)
                committed = true
            }
        } finally {
            if (!committed) installer.abandonSession(id)
        }
    }

    companion object {
        private val checkLock = Mutex()

        private val step = MutableStateFlow<UpdateStep?>(null)

        @Synchronized
        private fun begin(context: Context): Boolean {
            if (step.value != null) return false
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val id = prefs.getInt(KEY_SESSION, NO_SESSION)
            if (id != NO_SESSION) {
                val installer = context.packageManager.packageInstaller
                val session = installer.getSessionInfo(id)
                if (session?.isSealed == true) {
                    step.value = UpdateStep.Installing
                    return false
                }
                if (session != null) installer.abandonSession(id)
                prefs.edit {
                    remove(KEY_SESSION)
                    remove(KEY_RELAUNCH)
                }
            }
            step.value = UpdateStep.Downloading(0)
            return true
        }

        internal fun owns(context: Context, session: Int): Boolean =
            session != NO_SESSION && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_SESSION, NO_SESSION) == session

        @Synchronized
        internal fun finish(context: Context, session: Int, installed: Boolean) {
            if (owns(context, session)) clear(context, installed)
        }

        @Synchronized
        private fun clear(context: Context, installed: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                remove(KEY_SESSION)
                if (!installed) remove(KEY_RELAUNCH)
            }
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
        private const val KEY_SESSION = "install_session"
        internal const val NO_SESSION = -1
        private const val KEY_RELAUNCH = "relaunch_after"
        private const val PERCENT = 100

        private const val PREFS = "updates"
        private const val KEY_CHECKED_AT = "checked_at"
        private const val KEY_AVAILABLE = "available"
        private const val FOLDER = "updates"

        private const val CHECK_EVERY_MILLIS = 6L * 60 * 60 * 1000
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 60L
        private const val MAX_MANIFEST_BYTES = 64L * 1024

        private val HTTP = OkHttpClient.Builder()
            .followSslRedirects(false)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        private const val BUFFER_BYTES = 64 * 1024
    }
}

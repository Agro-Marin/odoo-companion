package com.odoocompanion

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.room.Room
import com.odoocompanion.config.DeviceConfig
import com.odoocompanion.config.ManagedConfig
import com.odoocompanion.config.Settings
import com.odoocompanion.data.COMPANION_MIGRATIONS
import com.odoocompanion.data.CompanionDatabase
import com.odoocompanion.location.LocationForegroundService
import com.odoocompanion.net.OdooClient
import com.odoocompanion.softphone.SoftphoneLookup
import com.odoocompanion.softphone.SoftphonePlan
import com.odoocompanion.softphone.SoftphoneRetry
import com.odoocompanion.softphone.SoftphoneService
import com.odoocompanion.softphone.SoftphoneStore
import com.odoocompanion.sync.SyncScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CompanionApp : Application() {
    val config: DeviceConfig by lazy { DeviceConfig(this) }

    val database: CompanionDatabase by lazy {
        Room.databaseBuilder(this, CompanionDatabase::class.java, "companion.db")
            .addMigrations(*COMPANION_MIGRATIONS)
            .build()
    }

    val softphones: SoftphoneStore by lazy { SoftphoneStore(this) }

    val client: OdooClient by lazy {
        OdooClient(onServerNamedItself = { config.learnServerNamesItself() })
    }

    private val scope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Default +
            CoroutineExceptionHandler { _, cause -> Log.e(TAG, "Startup task failed", cause) },
    )

    private val restrictionsChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scope.launch { applyConfiguration() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            restrictionsChanged,
            IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        startup = scope.launch { applyConfiguration() }
        scope.launch { for (request in softphoneRefreshes) refreshSoftphoneSafely() }
    }

    internal lateinit var startup: Job
        private set

    suspend fun applyConfiguration() = reconfiguring.withLock {
        val managed = ManagedConfig.read(this)
        if (managed != null && config.applyManaged(managed)) {
            Log.i(TAG, "Applied managed configuration")
        }
        retryWhatTheLastBuildCouldNotRead()
        val settings = config.current()
        SyncScheduler.schedulePeriodicWork(this, settings)
        if (settings.isEnrolled) {
            LocationForegroundService.start(this)
        } else {
            LocationForegroundService.stop(this)
        }
        applySoftphone(settings.isEnrolled, SoftphoneLookup.Unavailable("not asked yet"))
        if (settings.isEnrolled) softphoneRefreshes.trySend(Unit)
    }

    // What Odoo says about the extension is asked outside the reconcile: it is a
    // network round trip, and inside it every save, policy push and boot waited
    // out OkHttp's timeouts -- on an emulator whose server accepted and stalled,
    // BOOT_COMPLETED was held 60 s and the system declared the app not
    // responding. The reconcile runs the line on the extension last stored
    // meanwhile. Requests are conflated: any number made while a lookup is out
    // become one more lookup after it.
    private val softphoneRefreshes = Channel<Unit>(Channel.CONFLATED)

    @Suppress("TooGenericExceptionCaught")
    private suspend fun refreshSoftphoneSafely() {
        try {
            refreshSoftphone()
        } catch (stopped: CancellationException) {
            throw stopped
        } catch (failure: Exception) {
            // the loop serves every later request; one failed refresh must not end it
            Log.e(TAG, "Softphone refresh failed", failure)
        }
    }

    private suspend fun refreshSoftphone() {
        val asked = config.currentOrNull()?.takeIf { it.isEnrolled } ?: return
        val lookup = client.softphone(asked)
        if (lookup is SoftphoneLookup.Unavailable) {
            Log.i(TAG, "Softphone settings not refreshed: ${lookup.reason}")
        } else if (lookup is SoftphoneLookup.Refused) {
            Log.w(TAG, "Odoo refused the device token (${lookup.code}); stopping the softphone")
        }
        reconfiguring.withLock {
            val now = config.current()
            // a reply about an enrolment that has since changed is not about this
            // one; the change asked again, and that lookup answers for it
            if (now.endpoint() != asked.endpoint()) return@withLock
            applySoftphone(now.isEnrolled, lookup)
        }
    }

    private fun Settings.endpoint() = Triple(baseUrl, identifier, token)

    // The company line rings here only on an enrolled handset whose user has an
    // extension in Odoo; with Odoo out of reach, the last extension it named
    // keeps ringing.
    private suspend fun applySoftphone(enrolled: Boolean, lookup: SoftphoneLookup) {
        when (val plan = SoftphonePlan.decide(enrolled, lookup, softphones.current())) {
            is SoftphonePlan.Run -> {
                softphones.save(plan.settings)
                if (!hasMicrophone()) {
                    // a line that would answer in silence is worse than none
                    Log.w(TAG, "Softphone not running: microphone permission not granted")
                    SoftphoneService.stop(this)
                    SoftphoneRetry.schedule(this)
                } else if (!SoftphoneService.start(this)) {
                    SoftphoneRetry.schedule(this)
                }
            }

            SoftphonePlan.Stop -> {
                softphones.save(null)
                SoftphoneService.stop(this)
            }
        }
    }

    private fun hasMicrophone(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private suspend fun retryWhatTheLastBuildCouldNotRead() {
        if (!config.consumeBuildChange(installedVersion())) return
        val revived = database.outbox().reviveUndecodable()
        if (revived > 0) {
            Log.i(TAG, "Queueing $revived row(s) the previous build could not decode")
        }
    }

    private val reconfiguring = Mutex()

    private fun installedVersion(): Long = runCatching {
        PackageInfoCompat.getLongVersionCode(
            packageManager.getPackageInfo(packageName, 0),
        )
    }.getOrDefault(0L)

    companion object {
        private const val TAG = "CompanionApp"

        fun from(context: Context): CompanionApp = context.applicationContext as CompanionApp
    }
}

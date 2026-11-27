package com.odoocompanion

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteException
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.room.Room
import com.odoocompanion.config.DeviceConfig
import com.odoocompanion.config.ManagedConfig
import com.odoocompanion.config.Settings
import com.odoocompanion.data.COMPANION_MIGRATIONS
import com.odoocompanion.data.CompanionDatabase
import com.odoocompanion.location.LocationForegroundService
import com.odoocompanion.net.OdooClient
import com.odoocompanion.push.PushBinding
import com.odoocompanion.push.PushCommand
import com.odoocompanion.push.PushKeyLookup
import com.odoocompanion.push.PushPlan
import com.odoocompanion.push.PushRegistration
import com.odoocompanion.scan.ScanLog
import com.odoocompanion.security.AdminGate
import com.odoocompanion.softphone.SoftphoneLookup
import com.odoocompanion.softphone.SoftphonePlan
import com.odoocompanion.softphone.SoftphoneRetry
import com.odoocompanion.softphone.SoftphoneService
import com.odoocompanion.softphone.SoftphoneStore
import com.odoocompanion.sync.SyncScheduler
import com.odoocompanion.ui.Appearance
import com.odoocompanion.ui.ScanWidget
import com.odoocompanion.update.Updater
import com.odoocompanion.whatsapp.DocumentReader
import com.odoocompanion.whatsapp.SightingStore
import com.odoocompanion.whatsapp.WhatsAppDocuments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.unifiedpush.android.connector.UnifiedPush
import java.io.File

open class CompanionApp : Application() {
    val config: DeviceConfig by lazy {
        DeviceConfig(this) { leaving -> database.outbox().claimUnstamped(leaving) }
    }

    val database: CompanionDatabase by lazy {
        Room.databaseBuilder(this, CompanionDatabase::class.java, "companion.db")
            .addMigrations(*COMPANION_MIGRATIONS)
            .build()
    }

    open val updater: Updater by lazy { Updater(this) }

    val softphones: SoftphoneStore by lazy { SoftphoneStore(this, config) }

    val documentReader by lazy { DocumentReader(cacheDir) }

    val whatsapp: SightingStore by lazy {
        SightingStore(File(filesDir, "whatsapp_sightings.json")) {
            WhatsAppDocuments(Environment.getExternalStorageDirectory()).scan()
        }
    }

    val scans: ScanLog by lazy { ScanLog(File(filesDir, "scans.json")) }

    var adminGate = AdminGate()

    val client: OdooClient by lazy {
        OdooClient(onServerNamedItself = config::learnServerNamesItself)
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
        Appearance.apply(this)
        ScanWidget.refresh(this)
        ContextCompat.registerReceiver(
            this,
            restrictionsChanged,
            IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        startup = scope.launch { applyConfiguration() }
        scope.launch { for (request in softphoneRefreshes) refreshSoftphoneSafely() }
        scope.launch { for (request in pushRefreshes) refreshPushSafely() }
    }

    override fun onTerminate() {
        scope.cancel()
        unregisterReceiver(restrictionsChanged)
        super.onTerminate()
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
        val moved = settings.enrollment != null && reconciledEnrollment != null &&
            settings.enrollment != reconciledEnrollment
        settings.enrollment?.let { reconciledEnrollment = it }
        SyncScheduler.schedulePeriodicWork(this, settings, newLink = moved)
        if (moved) SyncScheduler.uploadAfresh(this, settings.wifiOnlyUploads)
        if (settings.isEnrolled) {
            LocationForegroundService.start(this)
        } else {
            LocationForegroundService.stop(this)
        }
        applySoftphone(settings, SoftphoneLookup.Unavailable("not asked yet"))
        if (settings.isEnrolled) softphoneRefreshes.trySend(Unit)
        pushRefreshes.trySend(Unit)
    }

    private val pushBinding by lazy { PushBinding(this) }
    private val reportingPush = Mutex()

    fun obey(instance: String, command: PushCommand) = scope.launch {
        val settings = pushOwner(instance) ?: return@launch
        Log.i(TAG, "Odoo woke this handset: $command")
        when (command) {
            PushCommand.SYNC ->
                SyncScheduler.collectAndUploadNow(this@CompanionApp, settings.wifiOnlyUploads)

            PushCommand.SOFTPHONE -> softphoneRefreshes.trySend(Unit)
        }
    }

    fun reportPushEndpoint(instance: String, registration: PushRegistration?) =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            reportingPush.withLock {
                val settings = pushOwner(instance) ?: return@withLock
                when (val reply = client.registerPush(settings, registration)) {
                    is PushKeyLookup.Found -> Log.i(TAG, "Wake-ups registered: ${reply.registered}")
                    else -> Log.w(TAG, "Odoo did not take the push endpoint: $reply")
                }
            }
        }

    private suspend fun pushOwner(instance: String): Settings? = config.currentOrNull()
        ?.takeIf { it.isEnrolled && pushBinding.owns(it.enrollment, instance) }

    private val softphoneRefreshes = Channel<Unit>(Channel.CONFLATED)

    @Suppress("TooGenericExceptionCaught")
    private suspend fun refreshSoftphoneSafely() {
        try {
            refreshSoftphone()
        } catch (stopped: CancellationException) {
            throw stopped
        } catch (failure: Exception) {
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
            if (!now.sameCredentialsAs(asked)) return@withLock
            applySoftphone(now, lookup)
        }
    }

    private val pushRefreshes = Channel<Unit>(Channel.CONFLATED)

    @Suppress("TooGenericExceptionCaught")
    private suspend fun refreshPushSafely() {
        try {
            refreshPush()
        } catch (stopped: CancellationException) {
            throw stopped
        } catch (failure: Exception) {
            Log.e(TAG, "Push registration refresh failed", failure)
        }
    }

    private suspend fun refreshPush() {
        val settings = config.currentOrNull() ?: return
        val lookup = if (settings.isEnrolled) {
            client.pushKey(settings)
        } else {
            PushKeyLookup.Unavailable("not enrolled")
        }
        reconfiguring.withLock {
            if (config.current().sameCredentialsAs(settings)) applyPush(settings, lookup)
        }
    }

    private fun applyPush(settings: Settings, lookup: PushKeyLookup) {
        val plan = PushPlan.decide(
            settings.isEnrolled,
            lookup,
            UnifiedPush.getSavedDistributor(this),
            UnifiedPush.getDistributors(this),
        )
        when (plan) {
            is PushPlan.Register -> {
                val instance = pushBinding.activate(requireNotNull(settings.enrollment)) {
                    UnifiedPush.unregister(this, it)
                }
                UnifiedPush.saveDistributor(this, plan.distributor)
                UnifiedPush.register(this, instance, vapid = plan.vapidPublicKey)
            }

            PushPlan.Unregister -> pushBinding.disable { UnifiedPush.unregister(this, it) }

            PushPlan.NoDistributor ->
                Log.i(TAG, "No push distributor installed; the handset keeps its schedule")

            PushPlan.Keep -> Log.i(TAG, "Push registration not refreshed: $lookup")
        }
    }

    private suspend fun applySoftphone(owner: Settings, lookup: SoftphoneLookup) {
        when (
            val plan = SoftphonePlan.decide(
                owner.isEnrolled,
                lookup,
                softphones.current(owner)
            )
        ) {
            is SoftphonePlan.Run -> {
                softphones.save(owner, plan.settings)
                if (!hasMicrophone()) {
                    Log.w(TAG, "Softphone not running: microphone permission not granted")
                    SoftphoneService.stop(this)
                    SoftphoneRetry.schedule(this)
                } else if (!SoftphoneService.start(this)) {
                    SoftphoneRetry.schedule(this)
                }
            }

            SoftphonePlan.Stop -> {
                softphones.clear()
                SoftphoneService.stop(this)
            }
        }
    }

    private fun hasMicrophone(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private suspend fun retryWhatTheLastBuildCouldNotRead() {
        val version = packageManager.getPackageInfo(packageName, 0).longVersionCode
        try {
            config.onBuildChanged(version) {
                val revived = database.outbox().reviveUndecodable()
                if (revived > 0) {
                    Log.i(TAG, "Queueing $revived row(s) the previous build could not decode")
                }
            }
        } catch (failure: SQLiteException) {
            Log.w(
                TAG,
                "Queue recovery deferred until the next configuration pass (${failure.javaClass.simpleName})"
            )
        }
    }

    private val reconfiguring = Mutex()

    private var reconciledEnrollment: String? = null

    companion object {
        private const val TAG = "CompanionApp"

        fun from(context: Context): CompanionApp = context.applicationContext as CompanionApp
    }
}

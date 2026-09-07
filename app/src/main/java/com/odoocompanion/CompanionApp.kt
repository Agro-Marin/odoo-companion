package com.odoocompanion

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.room.Room
import com.odoocompanion.config.DeviceConfig
import com.odoocompanion.config.ManagedConfig
import com.odoocompanion.data.COMPANION_MIGRATIONS
import com.odoocompanion.data.CompanionDatabase
import com.odoocompanion.location.LocationForegroundService
import com.odoocompanion.net.OdooClient
import com.odoocompanion.sync.SyncScheduler
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

    val client: OdooClient by lazy { OdooClient() }

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
    }

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

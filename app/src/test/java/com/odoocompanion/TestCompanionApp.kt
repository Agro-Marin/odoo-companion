package com.odoocompanion

import android.os.Looper
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Robolectric selects Test<manifest application> before constructing the tests.
// Workers and application startup must see the same scheduler from the outset.
class TestCompanionApp : CompanionApp() {
    private val workers = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        val configuration = Configuration.Builder()
            .setExecutor(workers)
            .setMinimumLoggingLevel(Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(this, configuration)
        super.onCreate()
        runBlocking { startup.join() }
        check(!startup.isCancelled) { "Application startup failed" }
    }

    override fun onTerminate() {
        super.onTerminate()
        val manager = WorkManager.getInstance(this)
        val running = WorkQuery.fromStates(listOf(WorkInfo.State.RUNNING))
        val main = shadowOf(Looper.getMainLooper())
        runBlocking {
            withTimeout(10_000) {
                main.idle()
                while (manager.getWorkInfos(running).get().isNotEmpty()) {
                    delay(10)
                    main.idle()
                }
            }
        }
        // A worker can publish its final state before its completion callback reschedules work.
        // Drain that executor and its main-thread callbacks before releasing either database.
        workers.submit {}.get(10, TimeUnit.SECONDS)
        main.idle()
        manager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
        workers.submit {}.get(10, TimeUnit.SECONDS)
        main.idle()
        WorkManagerTestInitHelper.closeWorkDatabase()
        workers.shutdown()
        database.close()
    }
}

package com.odoocompanion

import android.os.Looper
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.testing.WorkManagerTestInitHelper
import com.odoocompanion.net.WireJson
import com.odoocompanion.update.UpdateFeed
import com.odoocompanion.update.UpdateManifest
import com.odoocompanion.update.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.robolectric.Shadows.shadowOf
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Robolectric selects Test<manifest application> before constructing the tests.
// Workers and application startup must see the same scheduler from the outset.
class TestCompanionApp : CompanionApp() {
    private val workers = Executors.newSingleThreadExecutor()

    override val updater: Updater by lazy {
        val current = UpdateManifest(1, "1", "test.apk", "0".repeat(64), 1)
        val feed = WireJson.encodeToString(UpdateManifest.serializer(), current)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.toString() != UpdateFeed.LATEST + UpdateFeed.MANIFEST) {
                throw IOException("No test fixture for this update asset")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(feed.toResponseBody()).build()
        }.build()
        Updater(this, client)
    }

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

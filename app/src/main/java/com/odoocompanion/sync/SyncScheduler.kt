package com.odoocompanion.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.odoocompanion.calllog.CallLogSyncWorker
import com.odoocompanion.config.Settings
import com.odoocompanion.recording.RecordingHarvestWorker
import java.util.concurrent.TimeUnit

object SyncScheduler {
    internal const val UPLOAD_PERIODIC = "upload-periodic"
    internal const val CALL_LOG_PERIODIC = "calllog-periodic"
    internal const val RECORDING_PERIODIC = "recording-periodic"
    internal const val CALL_LOG_NOW = "calllog-now"
    private const val UPLOAD_NOW = "upload-now"
    private const val COLLECT_NOW = "collect-now"
    const val UPLOAD_FOLLOWS = "upload-follows"

    fun schedulePeriodicWork(context: Context, settings: Settings) {
        val manager = WorkManager.getInstance(context)

        manager.enqueueUniquePeriodicWork(
            UPLOAD_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraints(settings.wifiOnlyUploads))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build(),
        )

        val collecting = settings.isEnrolled
        manager.reconcile(
            CALL_LOG_PERIODIC,
            wanted = collecting && settings.callLogEnabled,
            request = {
                PeriodicWorkRequestBuilder<CallLogSyncWorker>(30, TimeUnit.MINUTES).build()
            },
        )
        manager.reconcile(
            RECORDING_PERIODIC,
            wanted = collecting && settings.recordingsEnabled,
            request = {
                PeriodicWorkRequestBuilder<RecordingHarvestWorker>(1, TimeUnit.HOURS).build()
            },
        )
    }

    private fun WorkManager.reconcile(
        name: String,
        wanted: Boolean,
        request: () -> PeriodicWorkRequest,
    ) {
        if (wanted) {
            enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request())
        } else {
            cancelUniqueWork(name)
        }
    }

    fun collectAndUploadNow(context: Context, wifiOnlyUploads: Boolean) {
        WorkManager.getInstance(context)
            .beginUniqueWork(
                COLLECT_NOW,
                ExistingWorkPolicy.REPLACE,
                listOf(
                    OneTimeWorkRequestBuilder<CallLogSyncWorker>().addTag(UPLOAD_FOLLOWS).build(),
                    OneTimeWorkRequestBuilder<RecordingHarvestWorker>().addTag(
                        UPLOAD_FOLLOWS
                    ).build(),
                ),
            )
            .then(
                OneTimeWorkRequestBuilder<UploadWorker>()
                    .setConstraints(networkConstraints(wifiOnlyUploads))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
            .enqueue()
    }

    fun continueUpload(context: Context, wifiOnlyUploads: Boolean) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            UPLOAD_NOW,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            uploadRequest(wifiOnlyUploads),
        )
    }

    fun collectCallLogNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            CALL_LOG_NOW,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<CallLogSyncWorker>().build(),
        )
    }

    fun uploadNow(context: Context, wifiOnlyUploads: Boolean) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            UPLOAD_NOW,
            ExistingWorkPolicy.KEEP,
            uploadRequest(wifiOnlyUploads),
        )
    }

    private fun uploadRequest(wifiOnlyUploads: Boolean) = OneTimeWorkRequestBuilder<UploadWorker>()
        .setConstraints(networkConstraints(wifiOnlyUploads))
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()

    private fun networkConstraints(wifiOnly: Boolean): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()
}

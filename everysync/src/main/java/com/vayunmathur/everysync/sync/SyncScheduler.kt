package com.vayunmathur.everysync.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.vayunmathur.everysync.data.Settings
import java.util.concurrent.TimeUnit

/** Schedules background + on-demand syncs via WorkManager. */
object SyncScheduler {
    private const val PERIODIC_WORK = "EverySyncPeriodic"
    private const val IMMEDIATE_WORK = "EverySyncNow"

    /** Initial retry delay for a failed periodic sync; WorkManager doubles
     *  it exponentially from here (matching sibling workers, e.g. maps /
     *  downloadservice, which use the same 30s exponential policy). */
    private const val BACKOFF_SECONDS = 30L

    /** (Re)schedule the periodic background sync using the current global settings. */
    fun schedulePeriodic(context: Context) {
        val interval = Settings.intervalMinutes(context).coerceAtLeast(15)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (Settings.wifiOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(constraints)
            // SyncWorker returns Result.retry() on failure; without an
            // explicit policy WorkManager falls back to its default, so pin
            // the same exponential backoff sibling workers use.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request,
        )
    }

    /** Immediately sync a single account. */
    fun syncNow(context: Context, accountName: String) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(Data.Builder().putString(SyncWorker.KEY_ACCOUNT, accountName).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$IMMEDIATE_WORK-$accountName", ExistingWorkPolicy.REPLACE, request,
        )
    }

    /** Immediately sync every account. */
    fun syncAllNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK, ExistingWorkPolicy.REPLACE, request,
        )
    }
}

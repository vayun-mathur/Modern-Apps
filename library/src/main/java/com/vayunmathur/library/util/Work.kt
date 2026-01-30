package com.vayunmathur.library.util

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlin.time.Duration


inline fun <reified T: ListenableWorker> startRepeatedTask(context: Context, name: String, interval: Duration) {
    val workManager = WorkManager.getInstance(context)

    val myConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    // 1. ONE-TIME EXPEDITED TASK (To run immediately right now)
    // PeriodicWork cannot be expedited, so we run a one-off version for the "first run".
    val immediateRequest = OneTimeWorkRequestBuilder<T>()
        .setConstraints(myConstraints)
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .build()

    // 2. PERIODIC TASK (To schedule the future recurring runs)
    val hourlyRequest = PeriodicWorkRequestBuilder<T>(
        interval.inWholeMinutes, TimeUnit.MINUTES,
        5, TimeUnit.MINUTES
    )
        .setConstraints(myConstraints)
        .build()

    // Run the immediate task first
    workManager.enqueueUniqueWork(
        "${name}_immediate",
        ExistingWorkPolicy.KEEP,
        immediateRequest
    )

    // Schedule the recurring task
    workManager.enqueueUniquePeriodicWork(
        "${name}_periodic",
        ExistingPeriodicWorkPolicy.REPLACE,
        hourlyRequest
    )
}
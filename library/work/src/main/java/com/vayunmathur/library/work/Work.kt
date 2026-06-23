package com.vayunmathur.library.work

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

inline fun <reified T : ListenableWorker> startRepeatedTask(
    context: Context,
    name: String,
    interval: Duration,
    oneTimeWorkPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
    periodicWorkPolicy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP
) {
    val workManager = WorkManager.getInstance(context)

    val myConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    val immediateRequest = OneTimeWorkRequestBuilder<T>()
        .setConstraints(myConstraints)
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .build()

    val hourlyRequest = PeriodicWorkRequestBuilder<T>(
        interval.inWholeMinutes, TimeUnit.MINUTES,
        5, TimeUnit.MINUTES
    )
        .setConstraints(myConstraints)
        .build()

    workManager.enqueueUniqueWork(
        "${name}_immediate",
        oneTimeWorkPolicy,
        immediateRequest
    )

    workManager.enqueueUniquePeriodicWork(
        "${name}_periodic",
        periodicWorkPolicy,
        hourlyRequest
    )
}

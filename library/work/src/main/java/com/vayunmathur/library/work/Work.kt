package com.vayunmathur.library.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

inline fun <reified T : ListenableWorker> startRepeatedTask(
    context: Context,
    name: String,
    interval: Duration,
    oneTimeWorkPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
    periodicWorkPolicy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP
) = enqueueRepeatedTask(T::class.java, context, name, interval, oneTimeWorkPolicy, periodicWorkPolicy)

/**
 * Non-inline core of [startRepeatedTask]. Split out so the scheduling logic
 * lives in one compiled body instead of being copied into every call site
 * (which also left the library's own class file with no executed
 * instructions to measure).
 */
@PublishedApi
internal fun <T : ListenableWorker> enqueueRepeatedTask(
    workerClass: Class<T>,
    context: Context,
    name: String,
    interval: Duration,
    oneTimeWorkPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
    periodicWorkPolicy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP
) {
    require(workerClass.name.isNotEmpty()) { "worker class required" }
    val workManager = WorkManager.getInstance(context)

    val myConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    val immediateRequest = androidx.work.OneTimeWorkRequest.Builder(workerClass)
        .setConstraints(myConstraints)
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .build()

    val hourlyRequest = androidx.work.PeriodicWorkRequest.Builder(
        workerClass,
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

package com.vayunmathur.findfamily.platform

import android.content.Context
import com.vayunmathur.library.log.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.data.NoShowAlert
import com.vayunmathur.findfamily.domain.NoShowPolicy
import com.vayunmathur.findfamily.util.notifyNoShowAlert
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Scheduler + worker for no-show alerts (issue 702).
 *
 * Each alert gets one inexact [androidx.work.OneTimeWorkRequest] firing at its
 * deadline ([NoShowAlert.expectedAt] + [NoShowAlert.grace]), enqueued as unique
 * work per alert id so re-arming replaces rather than duplicates. Deliberately
 * inexact WorkManager only — no `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`.
 *
 * The actual due/arrival decision lives in [NoShowPolicy] (pure, unit-tested);
 * this file is only the Android glue: enqueue at the deadline, sweep when the
 * work runs, and re-enqueue everything after reboot / process init.
 *
 * User-visible delivery happens in [NoShowCheckWorker.doWork]: every alert
 * [sweepNow] claims is posted via `notifyNoShowAlert`. Claiming without
 * notifying would silently delete one-shot watches, so the two stay together.
 */
class NoShowCheckWorker(
    appContext: Context,
    workerParams: androidx.work.WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = try {
        val fired = NoShowCheckScheduler.sweepNow(applicationContext)
        if (fired.isNotEmpty()) {
            Log.status(TAG, "sweep fired ${fired.size} alert(s): ${fired.map { it.id }}")
            val repository = FindFamilyRepository.get(applicationContext)
            for (alert in fired) {
                // A claimed one-shot is already deleted by the sweep, so a
                // delivery failure here cannot be retried by re-running the
                // sweep — log it, but still deliver every other alert.
                runCatching { deliver(applicationContext, repository, alert) }
                    .onFailure { e -> Log.status(TAG, "deliver alert ${alert.id} failed", e) }
            }
        }
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }

    /** Post the user-visible notification for one claimed [alert]. */
    private suspend fun deliver(
        context: Context,
        repository: FindFamilyRepository,
        alert: NoShowAlert,
    ) {
        val user = repository.getUser(alert.watchedUserId) ?: return
        val waypoint = alert.waypointId?.let { runCatching { repository.getWaypoint(it) }.getOrNull() }
        val message = if (waypoint != null) {
            context.getString(R.string.notification_noshow_at_place, user.name, waypoint.name)
        } else {
            context.getString(R.string.notification_noshow_anywhere, user.name)
        }
        context.notifyNoShowAlert(user, message, alert.id, alert.waypointId)
    }

    companion object {
        private const val TAG = "FF-NoShow"
        internal const val KEY_ALERT_ID = "alert_id"
    }
}

object NoShowCheckScheduler {

    private const val TAG = "FF-NoShow"

    private fun workName(alertId: Long) = "noshow-$alertId"

    /**
     * Enqueue (or replace) the one-shot check for [alert] at its deadline.
     * Past-due deadlines enqueue with zero delay so they sweep immediately.
     */
    fun schedule(context: Context, alert: NoShowAlert) {
        val deadline = NoShowPolicy.deadline(alert.expectedAt, alert.grace)
        val delayMs = (deadline - Clock.System.now()).inWholeMilliseconds.coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<NoShowCheckWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(NoShowCheckWorker.KEY_ALERT_ID to alert.id))
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(workName(alert.id), ExistingWorkPolicy.REPLACE, request)
    }

    /** Drop the pending check for [alertId], if any (e.g. alert deleted). */
    fun cancel(context: Context, alertId: Long) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(alertId))
    }

    /**
     * Check every due alert against the latest fixes and claim the ones that
     * fire. Callable from the heartbeat / service loop as well as the worker.
     *
     * Claiming is atomic via `markFired` (0/1 rows), so concurrent sweeps
     * cannot double-fire. One-shot rows are deleted after claiming; recurring
     * rows stay with `fired = true` until re-armed. Returns the claimed
     * alerts for the notification step to deliver.
     */
    suspend fun sweepNow(context: Context): List<NoShowAlert> {
        val repository = FindFamilyRepository.get(context.applicationContext)
        val now = Clock.System.now()
        val due = repository.noShowAlertStore.getDue(now.epochSeconds)
        if (due.isEmpty()) return emptyList()
        val latestByUser = repository.latestLocationsOnce().associateBy { it.userid }
        val fired = mutableListOf<NoShowAlert>()
        for (alert in due) {
            val claimed = tryClaimAlert(repository, alert, latestByUser, now)
            if (claimed != null) fired.add(claimed)
        }
        return fired
    }

    /**
     * Evaluate one due alert and claim it if it should fire. Returns the claimed
     * (fired = true) copy, or null when this alert must not notify this sweep.
     */
    private suspend fun tryClaimAlert(
        repository: FindFamilyRepository,
        alert: NoShowAlert,
        latestByUser: Map<Long, com.vayunmathur.findfamily.data.LocationValue>,
        now: Instant,
    ): NoShowAlert? {
        val fix = latestByUser[alert.watchedUserId]
        val arrived = isArrived(repository, alert, fix)
        if (!NoShowPolicy.shouldFire(
                fired = alert.fired,
                expectedAt = alert.expectedAt,
                grace = alert.grace,
                now = now,
                arrived = arrived,
            )
        ) return null
        // Claim first: only the sweep whose UPDATE matches notifies.
        if (repository.noShowAlertStore.markFired(alert.id) != 1) return null
        if (alert.oneShot) runCatching { repository.noShowAlertStore.delete(alert) }
        return alert.copy(fired = true)
    }

    private suspend fun isArrived(
        repository: FindFamilyRepository,
        alert: NoShowAlert,
        fix: com.vayunmathur.findfamily.data.LocationValue?,
    ): Boolean {
        if (fix == null) return false
        val waypointId = alert.waypointId
        if (waypointId == null) {
            return NoShowPolicy.hasAnyFreshFix(
                fixTimestamp = fix.timestamp,
                expectedAt = alert.expectedAt,
                fixAccuracyMeters = fix.acc.toDouble(),
            )
        }
        val waypoint = runCatching { repository.getWaypoint(waypointId) }.getOrNull()
            ?: return false
        return NoShowPolicy.hasArrivedAtWaypoint(
            fixLat = fix.coord.lat,
            fixLon = fix.coord.lon,
            fixAccuracyMeters = fix.acc.toDouble(),
            fixTimestamp = fix.timestamp,
            expectedAt = alert.expectedAt,
            waypointLat = waypoint.coord.lat,
            waypointLon = waypoint.coord.lon,
            waypointRangeMeters = waypoint.range,
        )
    }

    /**
     * Re-enqueue checks for every unfired alert. Call from `BootReceiver` and
     * VM / process init — the mirror of `applyDueAutoToggles`, which the
     * heartbeat re-applies the same way rather than trusting settled state.
     */
    suspend fun rescheduleAll(context: Context) {
        val repository = FindFamilyRepository.get(context.applicationContext)
        val pending = repository.noShowAlertStore.getAll().filter { !it.fired }
        for (alert in pending) {
            runCatching { schedule(context, alert) }
                .onFailure { e -> Log.status(TAG, "reschedule alert ${alert.id} failed", e) }
        }
        Log.status(TAG, "rescheduled ${pending.size} no-show alert(s)")
    }

    /** Convenience: schedule exactly at the deadline computed from these parts. */
    fun scheduleAt(
        context: Context,
        alertId: Long,
        expectedAt: kotlin.time.Instant,
        grace: Duration,
    ) {
        val now = Clock.System.now()
        val delayMs = ((expectedAt + grace) - now).inWholeMilliseconds.coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<NoShowCheckWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(NoShowCheckWorker.KEY_ALERT_ID to alertId))
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(workName(alertId), ExistingWorkPolicy.REPLACE, request)
    }
}

package com.vayunmathur.screentime.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "ScreenTimeReceiver"

/**
 * Fired by `UsageStatsService` when an app has spent its timer budget.
 *
 * The platform delivers the `PendingIntent` registered with the observer, so this is the
 * moment the timer is actually reached rather than the moment it was set.
 */
class TimerReachedReceiver : BroadcastReceiver() {

    // Broad catch is deliberate: onReceive must not throw, and the coordinator path
    // throws undocumented RuntimeExceptions (not just SQLiteException).
    @Suppress("TooGenericExceptionCaught")
    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: run {
            Log.status(TAG, "timer callback with no package")
            return
        }
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                Coordinator(app).onTimerReached(packageName)
            } catch (e: Exception) {
                Log.error(TAG, "could not enforce the timer for $packageName", e)
            } finally {
                pending.finish()
            }
        }
    }

    /** Intent factory for [TimerReachedReceiver]. */
    companion object {
        fun intent(context: Context, packageName: String): Intent =
            Intent(context, TimerReachedReceiver::class.java)
                .putExtra(EXTRA_PACKAGE_NAME, packageName)

        /** Enforce immediately, for an app already over budget when the observer is armed. */
        fun enforceNow(context: Context, packageName: String) {
            val app = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { Coordinator(app).onTimerReached(packageName) }
                    .onFailure { Log.error(TAG, "could not enforce $packageName", it) }
            }
        }
    }
}

/** Fired at each wind-down boundary. Re-arms the next one through [Coordinator]. */
class ScheduleReceiver : BroadcastReceiver() {

    // Broad catch is deliberate: onReceive must not throw, and the coordinator path
    // throws undocumented RuntimeExceptions (not just SQLiteException).
    @Suppress("TooGenericExceptionCaught")
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                Coordinator(app).reconcile()
            } catch (e: Exception) {
                Log.error(TAG, "schedule reconcile failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    /** Intent factory for [ScheduleReceiver]. */
    companion object {
        fun intent(context: Context): Intent = Intent(context, ScheduleReceiver::class.java)
    }
}

/**
 * Reconciles when wall-clock time or timezone changes.
 *
 * A timezone/time change moves midnight and every wind-down boundary, and
 * invalidates alarms armed against the old zone. Following [BootReceiver]'s
 * pattern: funnel through [Coordinator.reconcile], which re-prunes spent
 * state, re-arms observers and re-arms alarms (including next-midnight).
 */
class TimeChangedReceiver : BroadcastReceiver() {

    @Suppress("TooGenericExceptionCaught")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in TIME_CHANGE_ACTIONS) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                Coordinator(app).reconcile()
            } catch (e: Exception) {
                Log.error(TAG, "time-change reconcile failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val TIME_CHANGE_ACTIONS = setOf(
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
        )
    }
}

/**
 * Re-establishes enforcement after a reboot.
 *
 * Alarms and usage observers are both process- and boot-scoped, so without this a restart
 * silently ends timers and schedules until something else reconciles. `SpentState` survives
 * in SharedPreferences precisely so a reboot cannot hand back a spent budget.
 */
class BootReceiver : BroadcastReceiver() {

    // Broad catch is deliberate: onReceive must not throw, and the coordinator path
    // throws undocumented RuntimeExceptions (not just SQLiteException).
    @Suppress("TooGenericExceptionCaught")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                Coordinator(app).reconcile()
            } catch (e: Exception) {
                Log.error(TAG, "boot reconcile failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}


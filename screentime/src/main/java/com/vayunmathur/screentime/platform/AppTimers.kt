package com.vayunmathur.screentime.platform

import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.Context
import com.vayunmathur.library.log.Log
import androidx.core.content.getSystemService
import com.vayunmathur.screentime.data.AppTimer
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

private const val TAG = "ScreenTimeTimers"

/**
 * Self-imposed per-app caps, timed by the platform's usage tracker.
 *
 * Unlike parental controls' `AppLimits` - which uses the `@SystemApi`
 * `registerAppUsageLimitObserver` waived for the supervision app - this uses the
 * `registerAppUsageObserver` / `registerUsageSessionObserver` pair, gated on `OBSERVE_APP_USAGE`
 * (see Phase 0a: `signature|privileged|role`, granted by `SYSTEM_WELLBEING`). Both are
 * `@SystemApi` members of the public `UsageStatsManager`, so - same rule as parental
 * controls - they are reached reflectively: a stub would shadow the real class for every
 * other caller. The platform counts foreground time and fires the callback when the budget
 * runs out.
 *
 * Observers count from midnight local time. [timeUsedToday] is passed at registration so that
 * re-registering mid-day - after a reboot, or after the user edits the timer - resumes against
 * time already spent rather than handing back a fresh budget.
 */
class AppTimers(private val context: Context) {

    private val usage = context.getSystemService<UsageStatsManager>()

    /** Register an observer for every timed app, replacing any previous registration. */
    fun sync(timers: List<AppTimer>) {
        val usage = usage ?: return
        for (timer in timers) {
            register(usage, timer.packageName, timer.dailyLimitMinutes)
        }
    }

    fun unregister(packageName: String) {
        val usage = usage ?: return
        val method = unregisterMethod ?: return
        runCatching { method.invoke(usage, observerId(packageName)) }
            .onFailure { Log.status(TAG, "could not unregister the observer for $packageName", it) }
    }

    private fun register(usage: UsageStatsManager, packageName: String, minutes: Int) {
        val method = registerMethod ?: return
        val usedMs = timeUsedToday(packageName).toMillis()
        val remainingMs = remainingMillis(minutes, usedMs)
        if (remainingMs <= 0L) {
            // Already over budget - enforce now rather than arming an observer that would
            // fire immediately with no useful callback context.
            TimerReachedReceiver.enforceNow(context, packageName)
            return
        }
        val pending = PendingIntent.getBroadcast(
            context,
            observerId(packageName),
            TimerReachedReceiver.intent(context, packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            // Remaining budget only, at millis precision: registering the full
            // minutes would hand a partially-used app a fresh full budget.
            method.invoke(
                usage,
                observerId(packageName),
                arrayOf(packageName),
                remainingMs,
                java.util.concurrent.TimeUnit.MILLISECONDS,
                pending,
            )
        }.onFailure { Log.status(TAG, "could not register an observer for $packageName", it) }
    }

    /**
     * Foreground time for [packageName] since local midnight.
     *
     * Public SDK (`queryAndAggregateUsageStats`) needing `PACKAGE_USAGE_STATS` - see
     * [UsageAccess]. Zero when the op is not held.
     */
    fun timeUsedToday(packageName: String): Duration {
        val usage = usage ?: return Duration.ZERO
        val midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val stats = runCatching {
            usage.queryAndAggregateUsageStats(midnight, System.currentTimeMillis())
        }.getOrElse {
            Log.status(TAG, "no usage stats; is PACKAGE_USAGE_STATS granted?", it)
            return Duration.ZERO
        }
        return Duration.ofMillis(stats[packageName]?.totalTimeInForeground ?: 0L)
    }

    /**
     * Total foreground time across all packages since local midnight.
     *
     * Sums the same per-package map as [timeUsedToday] so the dashboard and the timers agree
     * about what "used" means.
     */
    fun totalUsedToday(): Duration {
        val usage = usage ?: return Duration.ZERO
        val midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val stats = runCatching {
            usage.queryAndAggregateUsageStats(midnight, System.currentTimeMillis())
        }.getOrElse {
            Log.status(TAG, "no usage stats; is PACKAGE_USAGE_STATS granted?", it)
            return Duration.ZERO
        }
        return Duration.ofMillis(stats.values.sumOf { it.totalTimeInForeground })
    }

    /**
     * Per-package foreground map since local midnight, for the dashboard list.
     *
     * One query serves the whole screen; callers filter to launchable packages themselves.
     *
     * Null when the usage-stats op is not held or the query fails - which the dashboard must
     * render as a permission prompt, not as "0 min". An empty (non-null) map means access is
     * granted but nothing has been used yet. Entries with zero time are dropped here; the
     * ViewModel keeps sub-minute entries (millis > 0) so they render as "<1 min".
     */
    fun usageTodayByPackage(): Map<String, Long>? {
        val usage = usage ?: return null
        if (!UsageAccess.isGranted(context)) return null
        val midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return runCatching {
            usage.queryAndAggregateUsageStats(midnight, System.currentTimeMillis())
                .mapValues { (_, stats) -> stats.totalTimeInForeground }
                .filterValues { it > 0 }
        }.getOrElse {
            Log.status(TAG, "no usage stats; is PACKAGE_USAGE_STATS granted?", it)
            null
        }
    }

    /**
     * A stable per-package observer id.
     *
     * Same `hashCode` trade-off parental controls documents: a collision would merge two apps'
     * budgets, accepted because a persisted id table drifts across uninstalls.
     */
    private fun observerId(packageName: String): Int = packageName.hashCode()

    private companion object {
        val registerMethod: java.lang.reflect.Method? by lazy {
            systemApi(
                "registerAppUsageObserver",
                Int::class.javaPrimitiveType!!,
                Array<String>::class.java,
                Long::class.javaPrimitiveType!!,
                java.util.concurrent.TimeUnit::class.java,
                PendingIntent::class.java,
            )
        }

        val unregisterMethod: java.lang.reflect.Method? by lazy {
            systemApi(
                "unregisterAppUsageObserver",
                Int::class.javaPrimitiveType!!,
            )
        }

        fun systemApi(name: String, vararg params: Class<*>): java.lang.reflect.Method? =
            runCatching { UsageStatsManager::class.java.getMethod(name, *params) }
                .onFailure { Log.status(TAG, "UsageStatsManager.$name is unreachable", it) }
                .getOrNull()
    }
}

/** The package a [TimerReachedReceiver] broadcast refers to. Ours, not the platform's. */
const val EXTRA_PACKAGE_NAME = "com.vayunmathur.screentime.extra.PACKAGE_NAME"

/**
 * Budget left today: `limitMinutes * 60_000 - usedMs`, floored at 0.
 * Millis precision so a partially-used app does not regain whole minutes.
 */
internal fun remainingMillis(limitMinutes: Int, usedMs: Long): Long =
    (limitMinutes.toLong() * 60_000L - usedMs).coerceAtLeast(0L)

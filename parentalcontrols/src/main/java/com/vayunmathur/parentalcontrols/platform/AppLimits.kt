package com.vayunmathur.parentalcontrols.platform

import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.Context
import com.vayunmathur.library.log.Log
import androidx.core.content.getSystemService
import com.vayunmathur.parentalcontrols.data.AppRule
import com.vayunmathur.parentalcontrols.data.BonusGrant
import com.vayunmathur.parentalcontrols.platform.LimitReachedReceiver
import java.lang.reflect.Method
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

private const val TAG = "ParentalControlsLimits"

/**
 * Per-app daily caps, timed by the platform's usage tracker rather than by us.
 *
 * `registerAppUsageLimitObserver` is the right primitive and is meant for exactly this caller:
 * `UsageStatsService.registerAppUsageLimitObserver` waives the usual
 * `SUSPEND_APPS` + `OBSERVE_APP_USAGE` requirement for the active supervision app. The platform
 * counts foreground time for us and fires [callbackIntent] when the budget runs out, which is
 * both more accurate and far cheaper than polling `queryAndAggregateUsageStats`.
 *
 * ## Why reflection
 *
 * Both methods are `@SystemApi` members of `android.app.usage.UsageStatsManager`, which is an
 * ordinary public class. A `compileOnly` stub is the wrong tool for that shape - it would shadow
 * the real `UsageStatsManager` for every other caller in the app - so this follows the rule
 * `logviewer`'s `HiddenFrameworkApi` sets out and reaches them reflectively instead. The
 * reflection buys visibility, not privilege: `UsageStatsService` still applies its own check on
 * the other side, and it passes only because we hold the supervision role.
 *
 * Hidden-API enforcement would otherwise block this for a presigned, non-platform-signed app, so
 * `com.vayunmathur.parentalcontrols` is listed as `hidden-api-whitelisted-app` in
 * `vendor/modern-apps/sysconfig-modern-apps.xml`, the same way `cast` is.
 *
 * ## What "reached" means
 *
 * Observers count from midnight local time. [timeUsedToday] is passed at registration so that
 * re-registering mid-day - after a reboot, or after the parent edits the cap - resumes against
 * time already spent rather than handing the child a fresh budget. Without that, a reboot is an
 * unlimited-usage exploit.
 */
class AppLimits(private val context: Context) {

    private val usage = context.getSystemService<UsageStatsManager>()

    /** Register an observer for every capped app, replacing any previous registration. */
    fun sync(rules: List<AppRule>) {
        sync(rules, emptyList())
    }

    /**
     * Register an observer for every capped app, with today's bonus grants extending budgets.
     *
     * A grant for [BonusGrant.packageName] adds [BonusGrant.bonusMinutes] to that app's cap; a
     * grant with null package is device-wide and handled by the daily-limit path, not here.
     */
    fun sync(rules: List<AppRule>, bonuses: List<BonusGrant>) {
        val usage = usage ?: return
        val bonusByPackage = bonuses.filter { it.packageName != null }
            .groupBy { it.packageName!! }
            .mapValues { (_, grants) -> grants.sumOf { it.bonusMinutes } }
        for (rule in rules) {
            val minutes = rule.dailyLimitMinutes ?: continue
            val budget = minutes + (bonusByPackage[rule.packageName] ?: 0)
            register(usage, rule.packageName, budget)
        }
    }

    /**
     * Foreground time for [packageName] since local midnight.
     *
     * `queryAndAggregateUsageStats` is public SDK and needs `PACKAGE_USAGE_STATS`, which is an
     * app-op rather than a role grant - see the manifest note. If it is not held this returns
     * zero, and a cap then measures from the moment it was armed instead of from midnight.
     * Check [UsageAccess.isGranted] first when the distinction matters; this stays
     * zero-on-denied so observers always arm with a safe value.
     */
    fun timeUsedToday(packageName: String): Duration {
        val usage = usage ?: return Duration.ZERO
        val stats = runCatching {
            usage.queryAndAggregateUsageStats(midnightMillis(), System.currentTimeMillis())
        }.getOrElse {
            Log.status(TAG, "no usage stats; is PACKAGE_USAGE_STATS granted?", it)
            return Duration.ZERO
        }
        val millis = stats[packageName]?.totalTimeInForeground ?: 0L
        return Duration.ofMillis(millis)
    }

    /**
     * Total foreground time across all packages since local midnight.
     *
     * Used for the device-wide daily limit. Sums the same per-package map as [timeUsedToday],
     * so both paths agree about what "used" means.
     */
    fun totalUsedToday(): Duration {
        val usage = usage ?: return Duration.ZERO
        val stats = runCatching {
            usage.queryAndAggregateUsageStats(midnightMillis(), System.currentTimeMillis())
        }.getOrElse {
            Log.status(TAG, "no usage stats; is PACKAGE_USAGE_STATS granted?", it)
            return Duration.ZERO
        }
        return Duration.ofMillis(stats.values.sumOf { it.totalTimeInForeground })
    }

    private fun midnightMillis(): Long =
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun unregister(packageName: String) {
        val usage = usage ?: return
        val method = unregisterMethod ?: return
        runCatching { method.invoke(usage, observerId(packageName)) }
            .onFailure { Log.status(TAG, "could not unregister the observer for $packageName", it) }
    }

    private fun register(usage: UsageStatsManager, packageName: String, minutes: Int) {
        val method = registerMethod ?: return
        val limit = Duration.ofMinutes(minutes.toLong())
        val used = timeUsedToday(packageName)
        if (used >= limit) {
            // Already over budget. The platform rejects a null callback only when used < limit,
            // but there is no observer worth arming here - just enforce now.
            LimitReachedReceiver.enforceNow(context, packageName)
            return
        }
        val pending = PendingIntent.getBroadcast(
            context,
            observerId(packageName),
            LimitReachedReceiver.intent(context, packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            method.invoke(
                usage,
                observerId(packageName),
                arrayOf(packageName),
                limit,
                used,
                pending,
            )
        }.onFailure { Log.status(TAG, "could not register an observer for $packageName", it) }
    }

    /**
     * A stable per-package observer id.
     *
     * `hashCode` collides in principle. A collision would silently merge two apps' budgets, so
     * this is a real if unlikely bug; it is accepted because the alternative - a persisted id
     * table - has to stay in step with the rules table across uninstalls, and gets that wrong in
     * more situations than hashing collides in.
     */
    private fun observerId(packageName: String): Int = packageName.hashCode()

    private companion object {
        val registerMethod: Method? by lazy { systemApi("registerAppUsageLimitObserver",
            Int::class.javaPrimitiveType!!, Array<String>::class.java,
            Duration::class.java, Duration::class.java, PendingIntent::class.java) }

        val unregisterMethod: Method? by lazy { systemApi("unregisterAppUsageLimitObserver",
            Int::class.javaPrimitiveType!!) }

        fun systemApi(name: String, vararg params: Class<*>): Method? =
            runCatching { UsageStatsManager::class.java.getMethod(name, *params) }
                .onFailure { Log.status(TAG, "UsageStatsManager.$name is unreachable", it) }
                .getOrNull()
    }
}

/** The extras the platform attaches to the observer callback. */
object LimitExtras {
    const val OBSERVER_ID = "android.app.usage.extra.OBSERVER_ID"
    const val TIME_LIMIT = "android.app.usage.extra.TIME_LIMIT"
    const val TIME_USED = "android.app.usage.extra.TIME_USED"
}

/** The package a [LimitReachedReceiver] broadcast refers to. Ours, not the platform's. */
const val EXTRA_PACKAGE_NAME = "com.vayunmathur.parentalcontrols.extra.PACKAGE_NAME"

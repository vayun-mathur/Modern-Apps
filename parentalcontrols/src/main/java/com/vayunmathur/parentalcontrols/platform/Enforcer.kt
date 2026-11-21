package com.vayunmathur.parentalcontrols.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import com.vayunmathur.library.log.Log
import com.vayunmathur.parentalcontrols.data.AppRule
import com.vayunmathur.parentalcontrols.data.BedtimeSchedule
import com.vayunmathur.parentalcontrols.data.BonusGrant
import com.vayunmathur.parentalcontrols.data.DowntimeSchedule
import com.vayunmathur.parentalcontrols.data.SchoolTimeSchedule
import com.vayunmathur.parentalcontrols.data.SupervisionRules
import com.vayunmathur.parentalcontrols.domain.AppRestriction
import com.vayunmathur.parentalcontrols.domain.EnforcementInput
import com.vayunmathur.parentalcontrols.domain.EnforcementReason
import com.vayunmathur.parentalcontrols.domain.decideEnforcement
import com.vayunmathur.parentalcontrols.notifications.LockNotifier
import java.time.LocalDate
import java.time.LocalDateTime

private const val TAG = "ParentalControlsEnforcer"

/** Minutes between the boundary check and the "was it just closed" comparison. */
private const val BOUNDARY_LOOKBACK_MINUTES = 1L

/**
 * Decides what each supervised app's state should be, and makes it so.
 *
 * Everything that can change an outcome funnels through [reconcile]: the bedtime alarm, a
 * usage observer firing, a rule edit, a clock/timezone change and boot. The decision itself
 * is the pure [decideEnforcement] in `domain/`, so it can be tested without the platform
 * supervision service (whose compile-only stub cannot even load on a host JVM).
 *
 * ## What changed, and why (audit fixes)
 *
 * 1. Downtime, school time and the device-wide limit are allow-list features: they now block
 *    every *launchable* app that is not approved — including apps with no rule row, which
 *    the previous iterate-the-rules-table implementation never blocked at all.
 * 2. The parent's per-app cap is no longer also written as a `TYPE_TIME_LIMIT` policy after
 *    the block/allow write. Upstream suspends a package the moment such a policy lands
 *    (standing `TODO(b/482425646)`), so that write silently replaced the real decision and
 *    permanently suspended every capped app. Timing is ours (usage observers); see
 *    [SupervisionPolicies].
 * 3. Packages we previously blocked but that are no longer in scope (rule deleted, app no
 *    longer launchable, master switched off) are explicitly allowed once — [ManagedPackages]
 *    remembers the set across reconciles, so a deleted rule cannot leave an app blocked
 *    forever.
 * 4. A per-app bonus grant now clears that package's spent mark ([onBonusGranted]): the old
 *    code extended only the observer budget while [LimitState] still said "reached", so a
 *    granted bonus could never actually reopen the app.
 * 5. The device-wide budget has a trigger of its own: [BedtimeScheduler] arms a periodic
 *    check while a device limit exists, and the spent transition is notified exactly once
 *    (marked in [LimitState]) instead of only being noticed on unrelated window boundaries.
 */
class Enforcer(private val context: Context) {

    private val rules = SupervisionRules.get(context)
    private val policies = SupervisionPolicies(context)
    private val limits = AppLimits(context)
    private val bedtime = BedtimeScheduler(context)
    private val limitState = LimitState(context)
    private val managed = ManagedPackages(context)

    /** Note that [packageName] has used up its daily budget, then reconcile. */
    suspend fun onLimitReached(packageName: String) {
        limitState.markReached(packageName)
        reconcile()
        // The observer fires exactly once per trip, so this notifies exactly once: the child
        // learns why the app just died. Window entries are notified by onWindowBoundary.
        LockNotifier.notifyLocked(context, BlockReason.AppLimit, packageName)
    }

    /**
     * Grant [minutes] of bonus time for [packageName] (null = device-wide), then reconcile.
     *
     * For a per-app grant the spent mark must be cleared: the extended budget is re-armed
     * with the usage observer by [reconcile], and the observer re-marks the package if the
     * extended budget is also spent. A device-wide grant needs no mark clearing — the
     * over-budget test is computed live against the extended budget.
     */
    suspend fun onBonusGranted(packageName: String?, minutes: Int) {
        rules.grantBonus(
            BonusGrant(
                packageName = packageName,
                bonusMinutes = minutes,
                day = LocalDate.now().toString(),
            ),
        )
        if (packageName != null) limitState.clearReached(packageName)
        reconcile()
    }

    /**
     * Bring the device in line with the stored rules, notifying for windows that just
     * *opened*. A boundary that ends a window stays silent.
     */
    suspend fun onWindowBoundary() {
        reconcile()
        val now = LocalDateTime.now()
        val minuteAgo = now.minusMinutes(BOUNDARY_LOOKBACK_MINUTES)
        val bedtime = rules.scheduleNow()
        val downtime = rules.downtimeNow()
        val school = rules.schoolTimeNow()
        val all = rules.allRulesNow()
        notifyBedtimeOpened(bedtime, now, minuteAgo, all)
        notifyDowntimeOpened(bedtime, downtime, now, minuteAgo, all)
        notifySchoolOpened(bedtime, downtime, school, now, minuteAgo, all)
    }

    private fun notifyWindowOpened(
        activeNow: Boolean,
        activeMinuteAgo: Boolean,
        packages: List<String>,
        reason: BlockReason,
    ) {
        if (!activeNow || activeMinuteAgo) return
        for (packageName in packages) LockNotifier.notifyLocked(context, reason, packageName)
    }

    private suspend fun notifyBedtimeOpened(
        bedtime: BedtimeSchedule,
        now: LocalDateTime,
        minuteAgo: LocalDateTime,
        all: List<AppRule>,
    ) = notifyWindowOpened(
        bedtime.activeAt(now), bedtime.activeAt(minuteAgo),
        all.filter { it.blockedAtBedtime }.map { it.packageName }, BlockReason.Bedtime,
    )

    private suspend fun notifyDowntimeOpened(
        bedtime: BedtimeSchedule,
        downtime: DowntimeSchedule,
        now: LocalDateTime,
        minuteAgo: LocalDateTime,
        all: List<AppRule>,
    ) {
        if (!downtime.activeAt(now) || downtime.activeAt(minuteAgo)) return
        val bedtimeNow = bedtime.activeAt(now)
        val byPackage = all.associateBy { it.packageName }
        // Downtime blocks every launchable unapproved app, not just apps with a rule row —
        // the notice set must match the blocked set or the child is never told why.
        val targets = (launchablePackages() + all.map { it.packageName })
            .filter { pkg ->
                val rule = byPackage[pkg]
                rule?.allowedInDowntime != true && !(rule?.blockedAtBedtime == true && bedtimeNow)
            }
        for (pkg in targets) LockNotifier.notifyLocked(context, BlockReason.Downtime, pkg)
    }

    private suspend fun notifySchoolOpened(
        bedtime: BedtimeSchedule,
        downtime: DowntimeSchedule,
        school: SchoolTimeSchedule,
        now: LocalDateTime,
        minuteAgo: LocalDateTime,
        all: List<AppRule>,
    ) {
        if (!school.activeAt(now) || school.activeAt(minuteAgo)) return
        if (downtime.activeAt(now)) return // downtime's notices already cover this minute
        val bedtimeNow = bedtime.activeAt(now)
        val byPackage = all.associateBy { it.packageName }
        val targets = (launchablePackages() + all.map { it.packageName })
            .filter { pkg ->
                val rule = byPackage[pkg]
                rule?.allowedInDowntime != true && !(rule?.blockedAtBedtime == true && bedtimeNow)
            }
        for (pkg in targets) LockNotifier.notifyLocked(context, BlockReason.SchoolTime, pkg)
    }

    /**
     * Bring the device in line with the stored rules.
     *
     * Safe to call redundantly - writing a policy that already matches is a no-op to the user,
     * and the platform de-duplicates the resulting hidden-state change.
     */
    suspend fun reconcile() {
        if (!policies.isAvailable) {
            Log.status(TAG, "no supervision policy channel; nothing will be enforced")
            return
        }
        // Master switch off ("Controls for this phone"): lift every block we manage, stop
        // timing, and arm nothing but the midnight reset.
        if (!SupervisionMaster.isEnabled(context)) {
            val stale = managed.replace(emptySet())
            for (packageName in stale) policies.allow(packageName)
            limits.sync(emptyList(), emptyList())
            bedtime.armAll(
                rules.scheduleNow().copy(enabled = false),
                rules.downtimeNow().copy(enabled = false),
                rules.schoolTimeNow().copy(enabled = false),
                deviceLimitActive = false,
            )
            return
        }
        val now = LocalDateTime.now()
        val bedtimeSchedule = rules.scheduleNow()
        val downtimeSchedule = rules.downtimeNow()
        val schoolSchedule = rules.schoolTimeNow()
        val all = rules.allRulesNow()
        val day = LocalDate.now().toString()
        val bonuses = rules.bonusesToday(day)
        limitState.pruneToToday()
        // Cheap and idempotent, and it has to happen before AppLimits.sync below: without the
        // usage-stats op an observer is armed with timeUsed = 0.
        if (UsageAccess.ensure(context) == UsageAccess.Status.DENIED) {
            Log.status(TAG, "usage-stats op denied; limits measure from arm time until it is granted")
        }

        val launchable = launchablePackages()
        val deviceLimitMinutes = rules.dailyLimitNow()
        val deviceOverBudget = deviceOverBudgetNow(bonuses, deviceLimitMinutes)
        // Notify the spent transition exactly once per day; a bonus that brings the device
        // back under budget re-arms the notice.
        if (deviceOverBudget && !limitState.hasDeviceReached()) {
            limitState.markDeviceReached()
            LockNotifier.notifyLocked(context, BlockReason.DailyLimit, null)
        } else if (!deviceOverBudget) {
            limitState.clearDeviceReached()
        }

        val plan = decideEnforcement(
            EnforcementInput(
                launchablePackages = launchable,
                restrictions = all.map {
                    AppRestriction(it.packageName, it.dailyLimitMinutes, it.blockedAtBedtime, it.allowedInDowntime)
                },
                bedtimeActive = bedtimeSchedule.activeAt(now),
                downtimeActive = downtimeSchedule.activeAt(now),
                schoolTimeActive = schoolSchedule.activeAt(now),
                deviceOverBudget = deviceOverBudget,
                reachedPackages = limitState.reachedPackages(),
            ),
        )

        val inScope = launchable + all.map { it.packageName }
        for (packageName in inScope) {
            if (packageName in plan) policies.block(packageName) else policies.allow(packageName)
        }
        // Anything we blocked before that has fallen out of scope (rule deleted, app
        // uninstalled/replaced, no longer launchable) is allowed exactly once.
        val stale = managed.replace(inScope.filter { it in plan }.toSet())
        for (packageName in stale) policies.allow(packageName)

        // Observers arm only for capped apps that are not already blocked for another
        // reason; a blocked app needs no timer, and arming one would re-fire on reopen.
        limits.sync(
            all.filter { it.dailyLimitMinutes != null && it.packageName !in plan },
            bonuses,
        )
        bedtime.armAll(
            bedtimeSchedule, downtimeSchedule, schoolSchedule,
            deviceLimitActive = deviceLimitMinutes != null,
        )
    }

    /**
     * Launchable packages a child could open, minus packages blocking would break:
     * ourselves (the parent must always be able to reach the controls), Settings, the
     * home/launcher package and the default dialer (calls stay available in every window,
     * matching the lock screens' promise).
     */
    internal fun launchablePackages(): Set<String> {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.queryIntentActivities(launcherIntent, 0)
        }
        val packages = resolved.mapNotNull { it.activityInfo?.packageName }.toMutableSet()
        packages.remove(context.packageName)
        packages.remove(SETTINGS_PACKAGE)
        resolveSinglePackage(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
        )?.let { packages.remove(it) }
        resolveSinglePackage(Intent(Intent.ACTION_DIAL))?.let { packages.remove(it) }
        return packages
    }

    private fun resolveSinglePackage(intent: Intent): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") context.packageManager.resolveActivity(intent, 0)
        }?.activityInfo?.packageName
    }.getOrNull()

    /** The user-facing reason an app is blocked. Ordering matches precedence. */
    enum class BlockReason {
        Bedtime,
        Downtime,
        SchoolTime,
        DailyLimit,
        AppLimit,
    }

    /**
     * Whether the device-wide budget (cap plus today's device-wide bonuses) is spent.
     *
     * Takes the already-loaded bonuses so [reconcile] does not read the table twice.
     */
    private fun deviceOverBudgetNow(
        bonuses: List<BonusGrant>,
        deviceLimitMinutes: Int?,
    ): Boolean {
        val deviceBonus = bonuses.filter { it.packageName == null }.sumOf { it.bonusMinutes }
        val deviceLimit = deviceLimitMinutes?.let { it + deviceBonus } ?: return false
        return limits.totalUsedToday() >= java.time.Duration.ofMinutes(deviceLimit.toLong())
    }

    private companion object {
        const val SETTINGS_PACKAGE = "com.android.settings"
    }
}

/** Maps the pure decision reason onto the user-facing [Enforcer.BlockReason]. */
fun EnforcementReason.toBlockReason(): Enforcer.BlockReason = when (this) {
    EnforcementReason.Bedtime -> Enforcer.BlockReason.Bedtime
    EnforcementReason.Downtime -> Enforcer.BlockReason.Downtime
    EnforcementReason.SchoolTime -> Enforcer.BlockReason.SchoolTime
    EnforcementReason.DailyLimit -> Enforcer.BlockReason.DailyLimit
    EnforcementReason.AppLimit -> Enforcer.BlockReason.AppLimit
}

/**
 * Packages this app has blocked and not yet un-blocked.
 *
 * Persisted so a rule deleted (or an app uninstalled) between reconciles cannot leave the
 * package suspended forever: the next reconcile allows anything recorded here that is no
 * longer meant to be blocked, then stores the new blocked set.
 */
class ManagedPackages(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Store [blocked] as the managed set; returns the previously managed packages now stale. */
    fun replace(blocked: Set<String>): Set<String> {
        val previous = prefs.getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet()
        prefs.edit { putStringSet(KEY_BLOCKED, blocked) }
        return previous - blocked
    }

    private companion object {
        const val PREFS = "parentalcontrols_managed"
        const val KEY_BLOCKED = "blocked"
    }
}

/**
 * Which apps have used up today's budget.
 *
 * Deliberately not in Room: it is per-day scratch state that must be readable from a broadcast
 * receiver on the main thread before any coroutine is available, and it is worthless after
 * midnight. SharedPreferences is the right weight for that.
 */
class LimitState(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markReached(packageName: String) {
        pruneToToday()
        val next = reached() + packageName
        prefs.edit {
            putStringSet(KEY_REACHED, next)
            putString(KEY_DAY, today())
        }
    }

    /** Forget a spent mark (a bonus grant reopens the app under its extended budget). */
    fun clearReached(packageName: String) {
        prefs.edit { putStringSet(KEY_REACHED, reached() - packageName) }
    }

    fun hasReached(packageName: String): Boolean = packageName in reached()

    /** Every package marked spent today; the decision input for [Enforcer.reconcile]. */
    fun reachedPackages(): Set<String> {
        pruneToToday()
        return reached()
    }

    fun markDeviceReached() = prefs.edit { putBoolean(KEY_DEVICE_REACHED, true) }

    fun clearDeviceReached() = prefs.edit { putBoolean(KEY_DEVICE_REACHED, false) }

    fun hasDeviceReached(): Boolean = prefs.getBoolean(KEY_DEVICE_REACHED, false)

    /** Drop yesterday's trips. This is the daily reset; there is no other. */
    fun pruneToToday() {
        if (prefs.getString(KEY_DAY, null) == today()) return
        prefs.edit {
            remove(KEY_REACHED)
            putBoolean(KEY_DEVICE_REACHED, false)
            putString(KEY_DAY, today())
        }
    }

    private fun reached(): Set<String> = prefs.getStringSet(KEY_REACHED, emptySet()) ?: emptySet()

    private fun today(): String = LocalDate.now().toString()

    private companion object {
        const val PREFS = "parentalcontrols_limits"
        const val KEY_REACHED = "reached"
        const val KEY_DEVICE_REACHED = "device_reached"
        const val KEY_DAY = "day"
    }
}

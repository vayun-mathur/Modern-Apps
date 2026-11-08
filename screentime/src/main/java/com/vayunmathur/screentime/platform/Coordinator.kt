package com.vayunmathur.screentime.platform

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.screentime.data.ScreenTimeRules
import com.vayunmathur.screentime.widget.WidgetRefresh
import java.time.LocalDate
import java.time.LocalDateTime

private const val TAG = "ScreenTimeCoordinator"

/**
 * Decides what Screen Time's state should be, and makes it so.
 *
 * Mirrors parental controls' `Enforcer.reconcile`: everything that can change an outcome -
 * the timer observer, a schedule boundary, a rule edit, boot - funnels through
 * [reconcile], recomputing the whole picture so the paths cannot disagree.
 *
 * Self-managed throughout: nothing here asks for a PIN, and every restriction is dismissible
 * by the user who set it. The paused set is the union of spent timers and the user's own
 * paused apps; wind-down never pauses, it only quiets (grayscale + DND).
 */
class Coordinator(private val context: Context) {

    private val rules = ScreenTimeRules.get(context)
    private val timers = AppTimers(context)
    private val suspender = Suspender(context)
    private val scheduler = WindowScheduler(context)
    private val windDown = WindDownApplier(context)
    private val spent = SpentState(context)

    /** Note that [packageName] has used up its timer, then reconcile. */
    suspend fun onTimerReached(packageName: String) {
        spent.markSpent(packageName)
        reconcile()
    }

    suspend fun reconcile() {
        UsageAccess.ensure(context)
        val now = LocalDateTime.now()
        val manualPaused = rules.pausedNow().pausedPackages.toSet()
        val windDownSchedule = rules.windDownNow()
        val allTimers = rules.allTimersNow()
        spent.pruneToToday()

        // Spent timers pause regardless of manual pauses. Un-spend nothing here: a spent
        // timer stays spent until midnight (the observer fired once and will not fire again).
        val timerPaused = allTimers
            .filter { spent.hasSpent(it.packageName) }
            .map { it.packageName }
            .toSet()

        suspender.sync(manualPaused + timerPaused)

        // Re-arm observers for unspent timers only; spent ones stay paused without an observer.
        timers.sync(allTimers.filter { !spent.hasSpent(it.packageName) })

        // Wind-down applies or clears on every reconcile so a mid-window edit (times changed,
        // toggles flipped) takes effect without waiting for the next boundary.
        val winding = windDownSchedule.activeAt(now)
        if (winding) windDown.enter(windDownSchedule.grayscale, windDownSchedule.doNotDisturb)
        else windDown.exit(windDownSchedule.grayscale, windDownSchedule.doNotDisturb)

        scheduler.armAll(windDownSchedule)

        // The widget tracks whatever reconcile just decided. Fire-and-forget: a missing host
        // must never break enforcement, and WidgetRefresh already swallows host absence.
        WidgetRefresh.refresh(context)
    }
}

/**
 * Which timers have been spent today.
 *
 * Same shape and rationale as parental controls' `LimitState`: per-day scratch that must be
 * readable without a coroutine and is worthless after midnight, so SharedPreferences rather
 * than Room. Midnight clears it, which is also what unpauses spent timers (via reconcile).
 */
class SpentState(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markSpent(packageName: String) {
        pruneToToday()
        prefs.edit().putStringSet(KEY_SPENT, spent() + packageName).apply()
    }

    fun hasSpent(packageName: String): Boolean = packageName in spent()

    /** Forget one package's spent mark, e.g. when its timer is deleted. */
    fun clear(packageName: String) {
        pruneToToday()
        prefs.edit().putStringSet(KEY_SPENT, spent() - packageName).apply()
    }

    /** Drop yesterday's trips. This is the daily reset; there is no other. */
    fun pruneToToday() {
        if (prefs.getString(KEY_DAY, null) == today()) return
        prefs.edit().remove(KEY_SPENT).putString(KEY_DAY, today()).apply()
    }

    private fun spent(): Set<String> = prefs.getStringSet(KEY_SPENT, emptySet()) ?: emptySet()

    private fun today(): String = LocalDate.now().toString()

    private companion object {
        const val PREFS = "screentime_spent"
        const val KEY_SPENT = "spent"
        const val KEY_DAY = "day"
    }
}

package com.vayunmathur.parentalcontrols.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "ParentalControlsReceiver"

/**
 * Fired by `UsageStatsService` when an app has spent its daily budget.
 *
 * The platform delivers the `PendingIntent` registered with the observer, so this is the moment
 * the limit is actually reached rather than the moment it was set - which is the whole reason
 * the timing is ours and not `TYPE_TIME_LIMIT`'s.
 */
class LimitReachedReceiver : BroadcastReceiver() {

    // Broad catch is deliberate: onReceive must not throw, and the enforcer path
    // throws undocumented RuntimeExceptions (not just SQLiteException).
    @Suppress("TooGenericExceptionCaught")
    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: run {
            Log.status(TAG, "limit callback with no package")
            return
        }
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Enforcer(app).onLimitReached(packageName)
            } catch (e: Exception) {
                Log.error(TAG, "could not enforce the limit for $packageName", e)
            } finally {
                pending.finish()
            }
        }
    }

    /** Intent factory and immediate-enforcement entry for [LimitReachedReceiver]. */
    companion object {
        fun intent(context: Context, packageName: String): Intent =
            Intent(context, LimitReachedReceiver::class.java)
                .putExtra(EXTRA_PACKAGE_NAME, packageName)

        /** Enforce immediately, for an app already over budget when the observer is armed. */
        fun enforceNow(context: Context, packageName: String) {
            val app = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { Enforcer(app).onLimitReached(packageName) }
                    .onFailure { Log.error(TAG, "could not enforce $packageName", it) }
            }
        }
    }
}

/**
 * Fired at each supervised-window boundary, at midnight, and by the periodic
 * device-limit check. Re-arms the next one through [Enforcer].
 */
class BedtimeReceiver : BroadcastReceiver() {

    // Broad catch is deliberate: onReceive must not throw, and the enforcer path
    // throws undocumented RuntimeExceptions (not just SQLiteException).
    @Suppress("TooGenericExceptionCaught")
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val periodic = intent.getBooleanExtra(EXTRA_PERIODIC_CHECK, false)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (periodic) {
                    // Budget check only: a plain reconcile (the device-spent transition is
                    // notified inside it), never window-entry notices for a non-boundary.
                    Enforcer(app).reconcile()
                } else {
                    // reconcile plus entry notifications for windows that just opened - not a
                    // bare reconcile, or the child would never learn why apps just closed.
                    // The midnight alarm also arrives here: reconcile prunes yesterday's
                    // spent marks and re-arms observers for the new day.
                    Enforcer(app).onWindowBoundary()
                }
            } catch (e: Exception) {
                Log.error(TAG, "window boundary failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    /** Intent factories for [BedtimeReceiver]. */
    companion object {
        fun intent(context: Context): Intent = Intent(context, BedtimeReceiver::class.java)

        /** The periodic device-limit check intent (see [BedtimeScheduler]). */
        fun periodicIntent(context: Context): Intent =
            Intent(context, BedtimeReceiver::class.java).putExtra(EXTRA_PERIODIC_CHECK, true)
    }
}

/** Extra marking a [BedtimeReceiver] broadcast as the periodic device-limit check. */
const val EXTRA_PERIODIC_CHECK = "com.vayunmathur.parentalcontrols.extra.PERIODIC_CHECK"

/**
 * Fired when the wall clock, date or timezone changes.
 *
 * Window alarms are armed as absolute instants computed in the then-current timezone, and
 * day-scoped state is keyed by local date — so a clock or timezone change (a routine travel
 * event, and also the obvious way for a child with Settings access to try to walk a bedtime
 * backwards) left every armed boundary stale until something else reconciled. Reconciling
 * re-derives windows, re-arms alarms in the new zone and prunes spent marks by the new date.
 * It cannot stop a determined child changing the clock — only removing their Settings
 * access does that, which is a supervision-policy decision for the OS, not this app — but it
 * removes the "change the clock and nothing notices" hole.
 */
class TimeChangedReceiver : BroadcastReceiver() {

    // Broad catch is deliberate: onReceive must not throw.
    @Suppress("TooGenericExceptionCaught")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in TIME_CHANGE_ACTIONS) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Enforcer(app).reconcile()
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
 * Re-establishes enforcement after this app itself is updated.
 *
 * An update replaces the APK and kills the process: alarms, usage observers and the
 * platform's service binding all have to be re-established, and until something
 * reconciles, supervision is silently off — the same hole [BootReceiver] closes for
 * reboots. `MY_PACKAGE_REPLACED` is the one lifecycle broadcast an app may receive
 * about *itself*; there is no equivalent for force-stop or uninstall, and pretending
 * otherwise is how fake "self-protection" features are built. See the self-protection
 * audit in `parentalcontrols/AGENTS.md`.
 */
class PackageReplacedReceiver : BroadcastReceiver() {

    // Broad catch is deliberate: onReceive must not throw, for the same reason as above.
    @Suppress("TooGenericExceptionCaught")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Enforcer(app).reconcile()
            } catch (e: Exception) {
                Log.error(TAG, "package-replaced reconcile failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Re-establishes enforcement after a reboot.
 *
 * Alarms and usage observers are both process- and boot-scoped, so without this a restart
 * silently ends supervision until something else happens to reconcile - and a child who learns
 * that is a child with no bedtime. `LimitState` survives in SharedPreferences precisely so a
 * reboot cannot hand back a spent budget.
 */
class BootReceiver : BroadcastReceiver() {

    // Broad catch is deliberate: onReceive must not throw, and the enforcer path
    // throws undocumented RuntimeExceptions (not just SQLiteException).
    @Suppress("TooGenericExceptionCaught")
    override fun onReceive(context: Context, intent: Intent) {
        // BOOT_COMPLETED only, deliberately not LOCKED_BOOT_COMPLETED: before the first
        // unlock this app's credential-protected storage (the Room rules database and the
        // SharedPreferences state) is unavailable, so a locked-boot reconcile could only
        // fail. BOOT_COMPLETED is delivered after unlock, when enforcement can actually
        // be re-established.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Enforcer(app).reconcile()
            } catch (e: Exception) {
                Log.error(TAG, "boot reconcile failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}


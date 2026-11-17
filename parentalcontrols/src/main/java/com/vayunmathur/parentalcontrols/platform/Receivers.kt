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

/** Fired at each supervised-window boundary. Re-arms the next one through [Enforcer]. */
class BedtimeReceiver : BroadcastReceiver() {

    // Broad catch is deliberate: onReceive must not throw, and the enforcer path
    // throws undocumented RuntimeExceptions (not just SQLiteException).
    @Suppress("TooGenericExceptionCaught")
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // reconcile plus entry notifications for windows that just opened - not a bare
                // reconcile, or the child would never learn why apps just closed.
                Enforcer(app).onWindowBoundary()
            } catch (e: Exception) {
                Log.error(TAG, "window boundary failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, BedtimeReceiver::class.java)
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
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
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


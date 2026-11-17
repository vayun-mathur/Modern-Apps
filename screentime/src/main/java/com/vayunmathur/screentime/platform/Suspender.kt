package com.vayunmathur.screentime.platform

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.edit
import com.vayunmathur.library.log.Log
import java.lang.reflect.Method

private const val TAG = "ScreenTimeSuspender"

/**
 * Pauses and unpauses apps by package name.
 *
 * `PackageManager.setPackagesSuspended` is `@SystemApi` on an ordinary public class, so -
 * exactly like parental controls' `AppLimits` - this reaches it reflectively instead of with
 * a stub (which would shadow the real `PackageManager` for every other caller). The
 * reflection buys visibility, not privilege: the platform checks `SUSPEND_APPS` on the other
 * side, and it passes only because we hold the `SYSTEM_WELLBEING` role.
 *
 * Hidden-API enforcement would otherwise block this for a presigned, non-platform-signed
 * app, so `com.vayunmathur.screentime` is listed as `hidden-api-whitelisted-app` in
 * `vendor/modern-apps/sysconfig-modern-apps.xml`.
 *
 * A suspended app's icon greys out and launches cancel: the platform shows the standard
 * paused treatment with a button into our `ACTION_SHOW_SUSPENDED_APP_DETAILS` activity
 * (see `Route.AppDetails`), which is where the timer/pause reason lives. Unsuspending is
 * unconditional: reconcile recomputes the full paused set every time, so a stale suspend
 * cannot survive a rule change, a reboot, or a timer deletion.
 */
// PrivateApi is a false positive here: setPackagesSuspended is a @SystemApi on a public
// class with no public SDK accessor; reflection is feature-gated and failure-degrading,
// and the platform still enforces SUSPEND_APPS (held via the SYSTEM_WELLBEING role).
@SuppressLint("PrivateApi")
class Suspender(private val context: Context) {

    private val pm = context.packageManager

    /** Pause [packages], unpausing everything else we previously paused. */
    fun sync(packages: Set<String>) {
        val previously = trackedPaused()
        val toPause = (packages - previously).toTypedArray()
        val toResume = (previously - packages).toTypedArray()
        if (toPause.isNotEmpty()) setSuspended(toPause, true)
        if (toResume.isNotEmpty()) setSuspended(toResume, false)
    }

    /** Every package we currently hold paused. */
    fun pausedNow(): Set<String> = trackedPaused()

    private fun setSuspended(packages: Array<String>, suspended: Boolean) {
        val method = setSuspendedMethod ?: return
        runCatching {
            // 5-arg overload: (String[], boolean, PersistableBundle, PersistableBundle,
            // SuspendDialogInfo). Null extras and null dialog: the platform shows its default
            // paused treatment, whose details button resolves to our AppDetails route.
            method.invoke(pm, packages, suspended, null, null, null)
            if (suspended) rememberPaused(packages.toSet()) else forgetPaused(packages.toSet())
        }.onFailure { Log.status(TAG, "could not set suspended=$suspended for ${packages.toList()}", it) }
    }

    private val prefs
        get() = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun trackedPaused(): Set<String> =
        prefs.getStringSet(KEY_PAUSED, emptySet()) ?: emptySet()

    private fun rememberPaused(packages: Set<String>) {
        prefs.edit { putStringSet(KEY_PAUSED, trackedPaused() + packages) }
    }

    private fun forgetPaused(packages: Set<String>) {
        prefs.edit { putStringSet(KEY_PAUSED, trackedPaused() - packages) }
    }

    private companion object {
        const val PREFS = "screentime_suspended"
        const val KEY_PAUSED = "paused"

        val setSuspendedMethod: Method? by lazy {
            runCatching {
                android.content.pm.PackageManager::class.java.getMethod(
                    "setPackagesSuspended",
                    Array<String>::class.java,
                    Boolean::class.javaPrimitiveType!!,
                    android.os.PersistableBundle::class.java,
                    android.os.PersistableBundle::class.java,
                    Class.forName("android.content.pm.SuspendDialogInfo"),
                )
            }.onFailure { Log.status(TAG, "PackageManager.setPackagesSuspended is unreachable", it) }
                .getOrNull()
        }
    }
}

package com.vayunmathur.games.hub.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri

/** What [GameLauncher.launchOrInstall] managed to do for a game. */
enum class LaunchOutcome {
    /** The game itself was started. */
    LAUNCHED,

    /** The game is not installed; its store listing was opened instead. */
    OPENED_STORE,

    /** Neither the game nor any store could be opened - the caller must tell the user. */
    UNAVAILABLE,
}

/**
 * Starting a game from the hub, and finding out which games are installed.
 *
 * Play used to call `getLaunchIntentForPackage` and silently do nothing when it returned
 * null (game never installed, or uninstalled while the hub was open). Now a missing game
 * falls through to its listing in the Modern Apps store, then to any store that handles
 * `market://`, and only reports [LaunchOutcome.UNAVAILABLE] when none exists.
 */
object GameLauncher {

    /** The Modern Apps store; it handles `market://details?id=<package>` listing links. */
    const val MODERN_APPS_STORE = "com.vayunmathur.appstore"

    fun launchOrInstall(context: Context, packageName: String): LaunchOutcome {
        val launch = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null && tryStart(context, launch)) return LaunchOutcome.LAUNCHED
        val listing = storeListingIntent(packageName)
        if (tryStart(context, Intent(listing).setPackage(MODERN_APPS_STORE))) return LaunchOutcome.OPENED_STORE
        if (tryStart(context, listing)) return LaunchOutcome.OPENED_STORE
        return LaunchOutcome.UNAVAILABLE
    }

    /** A `market://` listing link for [packageName], in its own task like a launcher start. */
    fun storeListingIntent(packageName: String): Intent =
        Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * The keys of [packagesByGameId] whose package is installed. Blocking package-manager
     * calls: run it off the main thread.
     */
    fun installedGameIds(context: Context, packagesByGameId: Map<String, String>): Set<String> {
        val pm = context.packageManager
        return packagesByGameId.filterValues { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }.keys
    }

    private fun tryStart(context: Context, intent: Intent): Boolean {
        if (intent.resolveActivity(context.packageManager) == null) return false
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

package com.vayunmathur.launcher.tv.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/** One launchable tile on the home screen. */
data class TvAppEntry(
    val packageName: String,
    val label: String,
    val leanback: Boolean,
)

/**
 * Discovers launchable apps, leanback-first (the tv-detox `appList`/`showAddMenu` logic).
 *
 * Leanback intent first, phone launcher intent as fallback — a sideloaded phone app on a TV
 * box still gets a tile rather than vanishing. System launchers and settings surfaces from
 * [TvRowOrder.NEVER_TILES] never become tiles, except the Play Store, which owns a bottom-row
 * slot of its own.
 */
object TvApps {
    fun installed(context: Context, selfPackage: String): List<TvAppEntry> {
        val pm = context.packageManager
        val leanbackPkgs = queryCategory(pm, Intent.CATEGORY_LEANBACK_LAUNCHER)
        val phonePkgs = queryCategory(pm, Intent.CATEGORY_LAUNCHER)
        // Keyed by package: two apps can share a label (locale duplicates, work-profile
        // clones), and keying by label would silently drop the second one.
        val seen = mutableSetOf<String>()
        val out = mutableListOf<TvAppEntry>()
        for (pkg in leanbackPkgs + phonePkgs) {
            val isTile = pkg != selfPackage && pkg !in seen &&
                (pkg !in com.vayunmathur.launcher.tv.domain.TvRowOrder.NEVER_TILES ||
                    pkg == com.vayunmathur.launcher.tv.domain.TvRowOrder.PLAY_STORE)
            if (isTile) {
                seen.add(pkg)
                out.add(TvAppEntry(pkg, labelOf(pm, pkg), pkg in leanbackPkgs))
            }
        }
        return out
    }

    fun launchIntent(pm: PackageManager, pkg: String): Intent? =
        pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)

    fun banner(pm: PackageManager, pkg: String): Drawable? {
        val launch = launchIntent(pm, pkg) ?: return appIcon(pm, pkg)
        return runCatching { pm.getActivityBanner(launch) }.getOrNull()
            ?: runCatching { pm.getApplicationBanner(pkg) }.getOrNull()
            ?: appIcon(pm, pkg)
    }

    fun appIcon(pm: PackageManager, pkg: String): Drawable? =
        runCatching { pm.getApplicationIcon(pkg) }.getOrNull()

    fun labelOf(pm: PackageManager, pkg: String): String =
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
            .getOrNull() ?: pkg

    fun isUserInstalled(pm: PackageManager, pkg: String): Boolean = runCatching {
        val info = pm.getApplicationInfo(pkg, 0)
        (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
    }.getOrDefault(false)

    private fun queryCategory(pm: PackageManager, category: String): List<String> {
        val q = Intent(Intent.ACTION_MAIN).addCategory(category)
        return runCatching { pm.queryIntentActivities(q, 0) }
            .getOrDefault(emptyList())
            .map { it.activityInfo.packageName }
            .distinct()
    }
}

/** Extra launch details the ViewModel hands to the open path. */
data class TvLaunchTarget(
    val intent: Intent? = null,
    val openSettings: Boolean = false,
    val openNetworkSettings: Boolean = false,
    val addApp: Boolean = false,
    val hdmiInputId: String? = null,
)


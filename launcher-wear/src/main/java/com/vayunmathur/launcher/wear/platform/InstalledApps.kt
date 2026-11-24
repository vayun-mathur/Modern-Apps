package com.vayunmathur.launcher.wear.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.vayunmathur.launcher.wear.domain.WatchApp
import com.vayunmathur.launcher.wear.domain.WearSettingsTarget
import com.vayunmathur.launcher.wear.domain.availableWearSettingsTargets

/**
 * Device enumeration for the watch drawer: MAIN+LAUNCHER activities via
 * PackageManager. Icons are loaded by the UI from PackageManager on-device;
 * they never travel over WearLink.
 */
object InstalledApps {
    fun load(context: Context): List<WatchApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .mapNotNull { info ->
                    val activity = info.activityInfo ?: return@mapNotNull null
                    WatchApp(
                        packageName = activity.packageName,
                        className = activity.name,
                        label = info.loadLabel(pm).toString(),
                    )
                }
        }.getOrDefault(emptyList())
    }

    /** The platform launch intent for [app], or null when it vanished since enumeration. */
    fun launchIntent(context: Context, app: WatchApp): Intent? {
        val explicit = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(app.packageName, app.className))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (context.packageManager.resolveActivity(explicit, 0) != null) {
            explicit
        } else {
            context.packageManager.getLaunchIntentForPackage(app.packageName)
        }
    }

    /** Settings rows whose system intent resolves on this watch (capability check). */
    fun settingsTargets(context: Context): List<WearSettingsTarget> {
        val pm = context.packageManager
        val resolvable = WearSettingsTarget.entries
            .filter { target ->
                pm.resolveActivity(Intent(target.action), PackageManager.MATCH_DEFAULT_ONLY) != null
            }
            .map { it.action }
            .toSet()
        return availableWearSettingsTargets(resolvable)
    }

    fun settingsIntent(target: WearSettingsTarget): Intent = Intent(target.action)

    /** Generic system-settings fallback entry point. */
    fun systemSettingsIntent(): Intent = Intent(Settings.ACTION_SETTINGS)
}

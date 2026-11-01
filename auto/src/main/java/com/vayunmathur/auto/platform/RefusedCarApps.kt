package com.vayunmathur.auto.platform

import android.content.ComponentName
import android.content.Context

/**
 * Car apps that rejected our handshake, keyed to the app version that refused.
 *
 * A rejection is the app's host validator turning us away (Google Messages only
 * allows Google's Android Auto), so retrying every session is pointless. An app
 * update clears the verdict, since a new version may allow us.
 */
object RefusedCarApps {
    fun isRefused(context: Context, component: ComponentName): Boolean {
        val refusedAt = prefs(context).getLong(component.flattenToString(), NOT_REFUSED)
        return refusedAt != NOT_REFUSED && refusedAt == versionCode(context, component.packageName)
    }

    fun markRefused(context: Context, component: ComponentName) {
        val version = versionCode(context, component.packageName) ?: return
        prefs(context).edit().putLong(component.flattenToString(), version).apply()
    }

    private fun versionCode(context: Context, packageName: String): Long? = runCatching {
        context.packageManager.getPackageInfo(packageName, 0).longVersionCode
    }.getOrNull()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private const val FILE = "ma_auto_refused_car_apps"
    private const val NOT_REFUSED = -1L
}

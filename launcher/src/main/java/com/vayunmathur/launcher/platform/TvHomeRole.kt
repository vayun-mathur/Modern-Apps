package com.vayunmathur.launcher.platform

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * The HOME-role plumbing that lets this app become the default launcher.
 *
 * Follows the :launcher `requestHomeRole` / `isDefaultHome` precedent: prefer the role
 * request when the platform offers it, otherwise fall back to the settings page where the
 * choice actually lives.
 */
object TvHomeRole {
    fun isDefaultHome(activity: Activity): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = activity.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == activity.packageName
    }

    fun requestHomeRole(activity: Activity): Boolean {
        val roleManager = activity.getSystemService(RoleManager::class.java)
        val intent = if (roleManager?.isRoleAvailable(RoleManager.ROLE_HOME) == true) {
            roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
        } else {
            Intent(Settings.ACTION_HOME_SETTINGS)
        }
        return runCatching {
            activity.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}


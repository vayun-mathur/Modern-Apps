package com.vayunmathur.appstore.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * What is on the device, and which of the store's sources offers it.
 *
 * Published in two waves on purpose. The package list itself is one cheap PackageManager
 * sweep and goes out immediately; launcher icons cost a binder call and a bitmap decode
 * each, so several hundred of them arrive afterwards. Doing it the other way round is
 * what used to leave the Installed tab blank for ten seconds.
 */
class InstalledAppsRepository(private val context: Context) {

    private val _apps = MutableStateFlow<List<InstalledInfo>>(emptyList())
    val apps: StateFlow<List<InstalledInfo>> = _apps.asStateFlow()

    private val _updatable = MutableStateFlow<List<InstalledInfo>>(emptyList())

    /**
     * [apps] minus the packages this device won't let the store install
     * ([RestrictedPackages]) or has already refused as incompatible
     * ([TvIncompatiblePackages]).
     *
     * They stay in [apps] — they are installed, and the library should say so — but every
     * update check reads this instead, so the store never offers an update it cannot apply.
     */
    val updatable: StateFlow<List<InstalledInfo>> = _updatable.asStateFlow()

    private val _icons = MutableStateFlow<Map<String, Drawable>>(emptyMap())
    val icons: StateFlow<Map<String, Drawable>> = _icons.asStateFlow()

    /** Re-read the device. Safe to call from `onResume`. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val userApps = try {
            pm.getInstalledApplications(PackageManager.MATCH_ALL)
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
        } catch (_: Exception) {
            emptyList<ApplicationInfo>()
        }

        val apps = userApps.mapNotNull { ai -> pm.readInstalledInfo(ai) }
            .sortedBy { it.name.lowercase() }
        _apps.value = apps

        val restricted = RestrictedPackages.forDevice(context) +
            TvIncompatiblePackages.forDevice(context)
        _updatable.value = apps.filterNot { it.packageName in restricted }

        _icons.value = userApps.mapNotNull { ai ->
            try {
                ai.packageName to pm.getApplicationIcon(ai.packageName)
            } catch (_: Exception) {
                null
            }
        }.toMap()
    }

    private fun PackageManager.readInstalledInfo(ai: ApplicationInfo): InstalledInfo? = try {
        val pi = getPackageInfo(ai.packageName, 0)
        // The OS fills this in for every install — including ones that predate the
        // Update-all ownership check — so no local history table needs backfilling (#885).
        // Null (adb installs, some system packages) never equals our package name,
        // which is exactly the "not managed by us" signal the caller wants.
        val source = runCatching { getInstallSourceInfo(ai.packageName) }.getOrNull()
        InstalledInfo(
            packageName = ai.packageName,
            name = getApplicationLabel(ai).toString(),
            versionName = pi.versionName,
            versionCode = pi.longVersionCode,
            lastUpdateTime = pi.lastUpdateTime,
            installerPackage = source?.installingPackageName,
            updateOwnerPackage = if (android.os.Build.VERSION.SDK_INT >=
                android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            ) {
                source?.updateOwnerPackageName
            } else {
                null
            },
        )
    } catch (_: Exception) {
        null
    }
}

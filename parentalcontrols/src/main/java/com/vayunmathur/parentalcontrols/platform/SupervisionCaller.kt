package com.vayunmathur.parentalcontrols.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.vayunmathur.library.log.Log

private const val TAG = "ParentalControlsIpc"

/** The only package allowed to ask this app anything. */
private const val SETTINGS_PACKAGE = "com.android.settings"

/**
 * Decides whether a caller may query supervision state.
 *
 * The platform's own `MessengerService` takes a `PermissionChecker` and refuses anything it turns
 * down; reimplementing the service means reimplementing that too, and leaving it out would have
 * been the easy mistake. This app holds `SYSTEM_SUPERVISION`, whose permission grant is broad
 * enough that an exported bound service answering everyone would be a way to read how a child's
 * device is supervised from any app on the system.
 *
 * The uid is the only part of an incoming [android.os.Message] that cannot be forged - `arg2`
 * carries a pid the client wrote itself - so the check is uid to package, never the reverse.
 */
class SupervisionCaller(context: Context) {

    private val packageManager = context.packageManager

    /** The Settings uid, resolved once; uids do not change while the system is running. */
    private val settingsUid: Int? = runCatching {
        // PackageInfoFlags.of is API 33+; on 31-32 the int-flags overload is the same query.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageUid(SETTINGS_PACKAGE, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") packageManager.getPackageUid(SETTINGS_PACKAGE, 0)
        }
    }.onFailure { Log.status(TAG, "could not resolve $SETTINGS_PACKAGE", it) }.getOrNull()

    fun isSettings(uid: Int): Boolean {
        // The system itself is allowed: Settings runs as the system uid on some configurations,
        // and a check that refused it would refuse the only caller this service has.
        if (uid == Process.SYSTEM_UID) return true
        return settingsUid != null && uid == settingsUid
    }
}

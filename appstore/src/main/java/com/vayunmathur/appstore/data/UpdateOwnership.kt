package com.vayunmathur.appstore.data

import android.content.Context
import android.content.pm.InstallSourceInfo
import android.os.Build
import com.vayunmathur.appstore.domain.UpdateEligibility

/**
 * Whether an installed package counts as managed by this store for bulk updates.
 *
 * "Update all" must not attempt packages this store has never installed: each one
 * costs a system confirmation dialog (or a doomed failure) and slows the whole run
 * while the user waits for it to fail. The signal is the OS's own installer record
 * rather than a locally kept history: PackageManager fills it in for every
 * install, including ones that predate this feature, so there is nothing to
 * backfill — and once this store updates a package itself (even by explicit tap),
 * the record names this store and the next "Update all" picks it up.
 *
 * The pure comparison lives in [UpdateEligibility] (unit-tested); this object is
 * only the thin PackageManager read over it.
 */
object UpdateOwnership {

    /**
     * True when this store installed [packageName] last, or owns its updates on
     * API 34+. Unknown packages (not installed, or an unreadable record such as an
     * adb install the OS reports nothing for) read as not managed.
     */
    fun installedByStore(context: Context, packageName: String): Boolean {
        val self = context.packageName
        val source = runCatching {
            context.packageManager.getInstallSourceInfo(packageName)
        }.getOrNull() ?: return false
        return UpdateEligibility.installedByStore(
            source.installingPackageName,
            source.updateOwnerOrNull(),
            self,
        )
    }

    private fun InstallSourceInfo.updateOwnerOrNull(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { updateOwnerPackageName }.getOrNull()
        } else {
            null
        }
}

package com.vayunmathur.parentalcontrols.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import androidx.core.content.getSystemService
import com.vayunmathur.parentalcontrols.domain.SETTINGS_PACKAGES
import com.vayunmathur.parentalcontrols.domain.isExempt

/**
 * Resolves the domain exemption (see `domain/ExemptApps.kt`) against this device.
 *
 * The home and dialer exemptions follow the *roles*: whichever package the system
 * currently reports as home screen or default dialer is exempt, so OEM launchers and
 * dialers are covered without naming them. Settings and this app are always exempt.
 * This is the one place that resolution happens — the supervisor app lists
 * ([SupervisionViewModel]) and enforcement ([Enforcer]) both consume it, so an app
 * that cannot be blocked is also never offered for blocking.
 */
object ExemptPackages {

    /** Every package that must never be blocked or offered for blocking on this device. */
    fun resolve(context: Context): Set<String> {
        val home = homePackage(context)
        val dialer = dialerPackage(context)
        val candidates = SETTINGS_PACKAGES + setOfNotNull(context.packageName, home, dialer)
        return candidates.filterTo(mutableSetOf()) {
            isExempt(it, selfPackage = context.packageName, homePackage = home, dialerPackage = dialer)
        }
    }

    /** Whether [packageName] is exempt on this device. */
    fun isExempt(context: Context, packageName: String): Boolean =
        packageName in resolve(context)

    private fun homePackage(context: Context): String? =
        resolveSinglePackage(context, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))

    private fun dialerPackage(context: Context): String? {
        // The default dialer is a role, not just an intent handler: TelecomManager
        // reports the role holder directly. The ACTION_DIAL resolution is the fallback
        // for builds where no default dialer is set.
        val telecom = runCatching { context.getSystemService<TelecomManager>()?.defaultDialerPackage }
            .getOrNull()
        if (!telecom.isNullOrBlank()) return telecom
        return resolveSinglePackage(context, Intent(Intent.ACTION_DIAL))
    }

    private fun resolveSinglePackage(context: Context, intent: Intent): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") context.packageManager.resolveActivity(intent, 0)
        }?.activityInfo?.packageName
    }.getOrNull()
}

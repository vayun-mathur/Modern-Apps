package com.vayunmathur.parentalcontrols.domain

/**
 * Apps supervision must never block, decided here so every flow agrees.
 *
 * Blocking the launcher strands the child on a dead home screen, blocking the dialer
 * removes emergency calling, and blocking Settings removes the parent's own route back
 * to these controls. The supervision app itself is exempt for the same reason: a limit
 * must never lock the parent out of the controls that set it.
 *
 * Exemption is by *role*, not by a hard-coded list of every OEM's packages: the platform
 * layer resolves which package currently holds the home and dialer roles and passes
 * those package names in. Only Settings is named here, because its package is fixed by
 * the platform. An exempt package is outside supervision entirely — it is not the same
 * as `allowedInDowntime`, which is a parent's per-window approval for a governed app.
 */

/** Why a package is exempt from blocking. */
enum class ExemptReason {
    /** The supervision app itself. */
    Self,

    /** The package holding the home / launcher role. */
    Home,

    /** The package holding the dialer role; calls stay available in every window. */
    Dialer,

    /** System Settings, the parent's route back to the controls. */
    Settings,
}

/** Settings packages fixed by the platform (AOSP and the Pixel variant of it). */
val SETTINGS_PACKAGES: Set<String> = setOf("com.android.settings")

/**
 * Why [packageName] is exempt, or null if it may be supervised.
 *
 * [homePackage] and [dialerPackage] are the role holders as resolved by the platform
 * layer; either may be null when the role cannot be resolved, in which case only the
 * self and Settings exemptions apply.
 */
fun exemptionFor(
    packageName: String,
    selfPackage: String?,
    homePackage: String?,
    dialerPackage: String?,
): ExemptReason? = when {
    packageName == selfPackage -> ExemptReason.Self
    packageName in SETTINGS_PACKAGES -> ExemptReason.Settings
    homePackage != null && packageName == homePackage -> ExemptReason.Home
    dialerPackage != null && packageName == dialerPackage -> ExemptReason.Dialer
    else -> null
}

/** Whether [packageName] must never be offered for, or subjected to, blocking. */
fun isExempt(
    packageName: String,
    selfPackage: String?,
    homePackage: String?,
    dialerPackage: String?,
): Boolean = exemptionFor(packageName, selfPackage, homePackage, dialerPackage) != null

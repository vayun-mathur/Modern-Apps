package com.vayunmathur.appstore.domain

/**
 * Whether an installed package counts as managed by this store for bulk updates.
 *
 * "Update all" must not attempt packages this store has never installed: each one
 * costs a system confirmation dialog (or a doomed failure) and slows the whole run.
 * The signal is the OS's own installer record — [android.content.pm.PackageManager]
 * fills it in for every install, including ones that predate this feature, so no
 * local history table is needed to backfill it:
 * - [installerPackage] (`installingPackageName`): the last installer of record.
 *   This store's own installs always carry it (explicit on API 34+ via
 *   `setInstallerPackageName`, filled from the calling UID below that).
 * - [updateOwnerPackage] (`updateOwnerPackageName`, API 34+): the update owner,
 *   which survives another store touching the package after us.
 *
 * Edge cases fall out of the comparison rather than special cases:
 * - sideloaded via adb: installer is null, never equal to us — skipped.
 * - Play-installed: installer is `com.android.vending` — skipped.
 * - installed by us before this feature existed: the OS record still names us.
 * - user explicitly taps Update on a skipped app: the single-install path never
 *   consults this, so it still works.
 */
object UpdateEligibility {

    fun installedByStore(
        installerPackage: String?,
        updateOwnerPackage: String?,
        ownPackage: String,
    ): Boolean = installerPackage == ownPackage || updateOwnerPackage == ownPackage
}

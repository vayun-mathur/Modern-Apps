package com.vayunmathur.findfamily.ui

import android.Manifest
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.findfamily.R
import com.vayunmathur.library.ui.AppPermissionsSpec
import com.vayunmathur.library.ui.IconBluetooth
import com.vayunmathur.library.ui.PermissionRequirement

// Staged permission requirements, split so no permission is asked for before
// the user's intent needs it (first-run census M):
//  - Pair/add family: no requirement at all (see FirstRunStage.PairFamily).
//  - Enable location sharing: foreground location, then background, then
//    Bluetooth scan — in that order, at the moment sharing is turned on.
//  - First alert (no-show watch / saved place): notifications, contextually.
// The full list below is only the returning-user gate: until this change
// every requirement was staged on the first frame, all-or-nothing.

/** Foreground location: fine AND coarse together (Android 12+ Precise/Approximate choice; fine alone is ignored). */
fun findFamilyForegroundLocationRequirement(): PermissionRequirement.Runtime =
    PermissionRequirement.Runtime(
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    )

/** Background location, requested only after foreground is granted. */
fun findFamilyBackgroundLocationRequirement(): PermissionRequirement.Runtime =
    PermissionRequirement.Runtime(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))

/**
 * Powered-off finder scan. The finder half is always on, so the scanner needs
 * BLUETOOTH_SCAN before the tracking service runs; without it it starts and
 * immediately closes. It now rides with enabling sharing, not the first frame.
 */
fun findFamilyBluetoothScanRequirement(): PermissionRequirement.Runtime =
    PermissionRequirement.Runtime(arrayOf(Manifest.permission.BLUETOOTH_SCAN))

/** Location-sharing requirements in request order: foreground → background → Bluetooth scan. */
fun findFamilyLocationSharingRequirements(): List<PermissionRequirement> = listOf(
    findFamilyForegroundLocationRequirement(),
    findFamilyBackgroundLocationRequirement(),
    findFamilyBluetoothScanRequirement(),
)

/** Notifications, asked for contextually at the first alert-relevant action, never at startup. */
fun findFamilyNotificationRequirement(): PermissionRequirement.Runtime =
    PermissionRequirement.notifications()

/** Every requirement the returning-user gate blocks on, in the original order. */
fun findFamilyFullRequirements(): List<PermissionRequirement> =
    findFamilyLocationSharingRequirements()

/** The full gate spec for returning users (unchanged from the original all-at-once wall). */
@Composable
fun findFamilyPermissionsSpec() = AppPermissionsSpec(
    title = stringResource(R.string.permission_grant_fine_location),
    icon = { IconBluetooth() },
    requirements = findFamilyFullRequirements(),
)

/** The location-sharing subset, for the staged first-run sheet's second stage. */
@Composable
fun findFamilyLocationSharingPermissionsSpec() = AppPermissionsSpec(
    title = stringResource(R.string.permission_grant_fine_location),
    icon = { IconBluetooth() },
    requirements = findFamilyLocationSharingRequirements(),
)

package com.vayunmathur.findfamily.ui

import android.Manifest
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.findfamily.R
import com.vayunmathur.library.ui.AppPermissionsSpec
import com.vayunmathur.library.ui.IconBluetooth
import com.vayunmathur.library.ui.PermissionRequirement

/** The foreground/background-location + BT-scan gate every FindFamily surface sits behind. */
@Composable
fun findFamilyPermissionsSpec() = AppPermissionsSpec(
    title = stringResource(R.string.permission_grant_fine_location),
    icon = { IconBluetooth() },
    requirements = listOf(
        // Request fine AND coarse together: on Android 12+
        // this surfaces the Precise/Approximate choice;
        // requesting fine alone is ignored by the system.
        PermissionRequirement.Runtime(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        ),
        PermissionRequirement.Runtime(
            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ),
        // The finder half of powered-off finding is always
        // on, so the scanner needs BLUETOOTH_SCAN from the
        // start; without it it starts and immediately closes.
        PermissionRequirement.Runtime(
            arrayOf(Manifest.permission.BLUETOOTH_SCAN)
        ),
    )
)

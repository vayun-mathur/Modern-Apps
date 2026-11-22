package com.vayunmathur.findfamily.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.findfamily.R
import com.vayunmathur.library.ui.AppPermissionsSpec
import com.vayunmathur.library.ui.IconBluetooth

/** The location-sharing subset, for the staged first-run sheet's second stage. */
@Composable
fun findFamilyLocationSharingPermissionsSpec() = AppPermissionsSpec(
    title = stringResource(R.string.permission_grant_fine_location),
    icon = { IconBluetooth() },
    requirements = findFamilyLocationSharingRequirements(),
)

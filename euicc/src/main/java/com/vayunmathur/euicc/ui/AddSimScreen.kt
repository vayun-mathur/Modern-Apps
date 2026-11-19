package com.vayunmathur.euicc.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.euicc.R
import com.vayunmathur.euicc.Route
import com.vayunmathur.library.ui.IconSim
import com.vayunmathur.library.ui.SetupAction
import com.vayunmathur.library.ui.SetupScaffold
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack

/**
 * How the user wants to add a SIM.
 *
 * The platform LPA splits this across an intro screen and a type picker, because it also
 * has carrier-app and device-transfer entry paths to offer. With only two ways in, a
 * second screen would be a page the user taps through without reading.
 */
@Composable
fun AddSimScreen(backStack: NavBackStack<Route>) {
    SetupScaffold(
        title = stringResource(R.string.add_profile_title),
        subtitle = stringResource(R.string.add_sim_body),
        icon = { IconSim() },
        backStack = backStack,
        primaryAction = SetupAction(
            label = stringResource(R.string.scan_qr_button),
            onClick = { backStack.add(Route.ScanQr) },
        ),
        secondaryAction = SetupAction(
            label = stringResource(R.string.enter_code_button),
            onClick = { backStack.add(Route.ActivationCode) },
        ),
    )
}

package com.vayunmathur.vpn.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.vpn.R
import com.vayunmathur.vpn.Route
import com.vayunmathur.vpn.ui.components.openVpnSettings

@Composable
fun SettingsPage(backStack: NavBackStack<Route>) {
    val context = LocalContext.current
    AppScaffold(
        title = stringResource(R.string.settings_about),
        backStack = backStack,
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()),
        ) {
            SettingsRow(
                title = stringResource(R.string.always_on_vpn),
                onClick = { openVpnSettings(context) },
            )
            SettingsRow(
                title = stringResource(R.string.bypass_list),
                onClick = { backStack.add(Route.BypassList) },
            )
        }
    }
}

package com.vayunmathur.euicc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.euicc.R
import com.vayunmathur.euicc.Route
import com.vayunmathur.euicc.data.Profile
import com.vayunmathur.euicc.platform.EuiccScreenState
import com.vayunmathur.library.ui.AppBarSize
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.IconSim
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SetupAction
import com.vayunmathur.library.ui.SetupScaffold
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack

/**
 * The profile list, and the screen Settings opens for "eSIM".
 *
 * Split into an enabled section and an "Available carriers" section the way the platform's
 * own LPA does, because on a phone the distinction is the whole point: exactly one profile
 * carries service at a time, and the rest are parked.
 *
 * With nothing installed the list would be two empty headings and an "Add SIM" row, so an
 * empty eUICC gets a [SetupScaffold] instead - which is what a new device actually shows.
 */
@Composable
fun EuiccHomeScreen(
    state: EuiccScreenState,
    backStack: NavBackStack<Route>,
    onReload: () -> Unit,
    onAddSim: () -> Unit,
) {
    val enabled = state.profiles.filter { it.isEnabled }
    val disabled = state.profiles.filterNot { it.isEnabled }

    if (state.profiles.isEmpty() && !state.loading && state.error == null) {
        SetupScaffold(
            title = stringResource(R.string.no_profiles_title),
            subtitle = stringResource(R.string.no_profiles_body),
            icon = { IconSim() },
            primaryAction = SetupAction(stringResource(R.string.add_profile_title), onAddSim),
        )
        return
    }

    AppScaffold(
        title = stringResource(R.string.app_name),
        size = AppBarSize.LargeFlexible,
        actions = {
            IconButton(onClick = onAddSim, enabled = !state.loading) { IconAdd() }
            IconButton(onClick = onReload, enabled = !state.loading) { IconRefresh() }
        },
    ) { pad ->
        Column(
            modifier = Modifier.fillMaxWidth().padding(pad),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                )
            }
            if (enabled.isNotEmpty()) {
                SettingsSection(title = stringResource(R.string.esim_enabled)) {
                    for (profile in enabled) ProfileRow(profile, backStack)
                }
            }
            if (disabled.isNotEmpty()) {
                SettingsSection(title = stringResource(R.string.available_carriers)) {
                    for (profile in disabled) ProfileRow(profile, backStack)
                }
            }
            SettingsSection {
                SettingsRow(
                    title = stringResource(R.string.add_profile_title),
                    enabled = !state.loading,
                    onClick = onAddSim,
                    leadingContent = { IconAdd() },
                )
                SettingsRow(
                    title = stringResource(R.string.device_info_title),
                    supportingText = state.eid?.let { stringResource(R.string.show_details) },
                    onClick = { backStack.add(Route.DeviceInfo) },
                )
            }
        }
    }
}

@Composable
private fun ProfileRow(profile: Profile, backStack: NavBackStack<Route>) {
    SettingsRow(
        title = profile.displayName,
        supportingText = profile.serviceProvider.ifBlank { profile.iccidDisplay },
        onClick = { backStack.pushOrReplace(Route.ProfileDetail(profile.iccid)) },
        leadingContent = { IconSim() },
        titleSharedKey = "euicc-profile-name-${profile.iccid}",
    )
}

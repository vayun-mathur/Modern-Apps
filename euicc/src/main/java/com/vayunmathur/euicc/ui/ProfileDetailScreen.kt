package com.vayunmathur.euicc.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.euicc.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.euicc.Route
import com.vayunmathur.euicc.data.Profile
import com.vayunmathur.euicc.platform.EuiccScreenState
import com.vayunmathur.euicc.ui.dialogs.RenameDialog
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.LoadingState
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedText

/**
 * One profile: what it is, and the three things that can be done to it.
 *
 * Enable, disable and erase all go through a [ConfirmDialog] because each one drops the
 * device's connection - switching carriers is not undoable from a spinner. The wording is
 * the platform LPA's, so the consequence reads the same as it would on stock.
 *
 * The profile is looked up from [state] by ICCID on every recomposition rather than passed
 * in, so it stays current across the reload that follows each mutation. If the lookup
 * fails the profile was erased, and the screen pops itself.
 */
@Composable
fun ProfileDetailScreen(
    iccid: String,
    state: EuiccScreenState,
    backStack: NavBackStack<Route>,
    onEnable: (Profile) -> Unit,
    onDisable: (Profile) -> Unit,
    onErase: (Profile) -> Unit,
    onRename: (Profile, String) -> Unit,
) {
    val profile = state.profiles.firstOrNull { it.iccid == iccid }
    if (profile == null) {
        // Still loading, erased, or the eUICC was re-read and no longer reports it.
        if (state.loading) {
            DetailScaffold(title = "", onNavigateBack = { backStack.pop() }) {
                LoadingState()
            }
        } else {
            backStack.pop()
        }
        return
    }

    var confirmEnable by remember { mutableStateOf(false) }
    var confirmDisable by remember { mutableStateOf(false) }
    var confirmErase by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    val label = profile.displayName
    val currentlyEnabled = state.profiles.firstOrNull { it.isEnabled }

    DetailScaffold(
        // The row the user tapped on the home list carries this name up into the bar. The slot
        // overload rather than the `backStack` one, which only takes a plain String.
        title = {
            Text(label, modifier = Modifier.sharedText("euicc-profile-name-${profile.iccid}"))
        },
        onNavigateBack = { backStack.pop() },
        actions = {
            TextButton(onClick = { renaming = true }, enabled = !state.loading) {
                Text(stringResource(R.string.rename_profile_title))
            }
        },
    ) {
        SettingsSection {
            SettingsRow(
                title = stringResource(R.string.profile_status),
                supportingText = stringResource(
                    if (profile.isEnabled) R.string.profile_status_enabled
                    else R.string.profile_status_disabled,
                ),
            )
            if (profile.serviceProvider.isNotBlank()) {
                SettingsRow(
                    title = stringResource(R.string.profile_provider),
                    supportingText = profile.serviceProvider,
                )
            }
            SettingsRow(
                title = stringResource(R.string.profile_iccid),
                supportingText = profile.iccidDisplay,
            )
        }
        SettingsSection {
            if (profile.isEnabled) {
                SettingsRow(
                    title = stringResource(R.string.disable_profile_title),
                    enabled = !state.loading,
                    onClick = { confirmDisable = true },
                )
            } else {
                SettingsRow(
                    title = stringResource(R.string.enable_profile_title),
                    enabled = !state.loading,
                    onClick = { confirmEnable = true },
                )
            }
            SettingsRow(
                title = stringResource(R.string.erase_sim_confirm_button),
                enabled = !state.loading,
                onClick = { confirmErase = true },
            )
        }
    }

    if (confirmEnable) {
        ConfirmDialog(
            title = stringResource(R.string.enable_carrier_dialog_title),
            message = currentlyEnabled?.let {
                stringResource(R.string.enable_carrier_dialog_text, label, it.displayName)
            } ?: stringResource(R.string.enable_carrier_dialog_text_no_current, label),
            confirmLabel = stringResource(R.string.enable_profile_title),
            dismissLabel = stringResource(UiR.string.cancel),
            onConfirm = { onEnable(profile) },
            onDismiss = { confirmEnable = false },
        )
    }
    if (confirmDisable) {
        ConfirmDialog(
            title = stringResource(R.string.disable_carrier_dialog_title),
            message = stringResource(R.string.disable_carrier_dialog_text, label),
            confirmLabel = stringResource(R.string.disable_profile_title),
            dismissLabel = stringResource(UiR.string.cancel),
            destructive = true,
            onConfirm = { onDisable(profile) },
            onDismiss = { confirmDisable = false },
        )
    }
    if (confirmErase) {
        ConfirmDialog(
            title = stringResource(R.string.erase_sim_dialog_title, label),
            message = stringResource(R.string.erase_sim_dialog_text, label),
            confirmLabel = stringResource(R.string.erase_sim_confirm_button),
            dismissLabel = stringResource(UiR.string.cancel),
            destructive = true,
            onConfirm = { onErase(profile) },
            onDismiss = { confirmErase = false },
        )
    }
    if (renaming) {
        RenameDialog(
            profile = profile,
            onConfirm = { onRename(profile, it); renaming = false },
            onDismiss = { renaming = false },
        )
    }
}

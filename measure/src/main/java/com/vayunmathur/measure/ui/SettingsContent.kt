package com.vayunmathur.measure.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.SettingsDivider
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.measure.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.measure.data.model.UnitSystem
import com.vayunmathur.measure.platform.SettingsActions
import com.vayunmathur.measure.platform.SettingsUiState

@Composable
fun SettingsContent(
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenSaved: () -> Unit = {},
) {
    AppScaffold(
        title = stringResource(UiR.string.settings),
        onNavigateBack = onBack,
    ) { padding ->
        // Single-pane settings form; letterboxed on expanded windows.
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
        Column(
            // The list is taller than the dialog on most phones; without this the
            // advanced section at the bottom is simply unreachable.
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(title = stringResource(R.string.settings_section_units)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_imperial_units),
                    supportingText = stringResource(R.string.settings_imperial_units_summary),
                    checked = state.unitSystem == UnitSystem.Imperial,
                    onCheckedChange = {
                        actions.setUnitSystem(if (it) UnitSystem.Imperial else UnitSystem.Metric)
                    },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_fractional_inches),
                    supportingText = stringResource(R.string.settings_fractional_inches_summary),
                    checked = state.useFractionalInches,
                    enabled = state.unitSystem == UnitSystem.Imperial,
                    onCheckedChange = actions::setUseFractionalInches,
                )
            }
            SettingsDivider()
            SettingsSection(title = stringResource(R.string.tool_compass)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_use_true_north),
                    supportingText = stringResource(R.string.settings_use_true_north_summary),
                    checked = state.useTrueNorth,
                    onCheckedChange = actions::setUseTrueNorth,
                )
            }
            SettingsDivider()
            SettingsSection(title = stringResource(R.string.settings_section_calibration)) {
                SettingsRow(
                    title = stringResource(R.string.tool_level),
                    supportingText = stringResource(
                        if (state.levelCalibrated) R.string.level_zeroed
                        else R.string.settings_level_uncalibrated_summary
                    ),
                    enabled = state.levelCalibrated,
                    onClick = actions::clearLevelCalibration,
                    trailingContent = {
                        if (state.levelCalibrated) Text(stringResource(UiR.string.clear))
                    },
                )
            }
            SettingsDivider()
            SettingsSection(title = stringResource(R.string.settings_section_general)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_haptic_feedback),
                    checked = state.hapticsEnabled,
                    onCheckedChange = actions::setHapticsEnabled,
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_keep_screen_on),
                    supportingText = stringResource(R.string.settings_keep_screen_on_summary),
                    checked = state.keepScreenOn,
                    onCheckedChange = actions::setKeepScreenOn,
                )
                SettingsRow(
                    title = stringResource(R.string.settings_saved_measurements),
                    onClick = onOpenSaved,
                )
            }
            SettingsDivider()
            SettingsSection(title = stringResource(R.string.settings_section_advanced)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_tracking_diagnostics),
                    supportingText = stringResource(
                        R.string.settings_tracking_diagnostics_summary
                    ),
                    checked = state.showDiagnostics,
                    onCheckedChange = actions::setShowDiagnostics,
                )
                if (state.showDiagnostics) {
                    SettingsRow(
                        title = stringResource(R.string.settings_open_diagnostics),
                        onClick = onOpenDiagnostics,
                    )
                }
            }
        }
        }
    }
}

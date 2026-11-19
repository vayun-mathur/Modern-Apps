package com.vayunmathur.measure.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SettingsDivider
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.Text
import com.vayunmathur.measure.R
import com.vayunmathur.measure.platform.DiagnosticsActions
import com.vayunmathur.measure.platform.DiagnosticsUiState

@Composable
fun DiagnosticsContent(
    state: DiagnosticsUiState,
    actions: DiagnosticsActions,
    onBack: () -> Unit = {},
) {
    AppScaffold(
        title = stringResource(R.string.diagnostics_title),
        onNavigateBack = onBack,
    ) { padding ->
        // Single-pane diagnostics list; letterboxed on expanded windows.
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
        Column(
            modifier = Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSection(title = stringResource(R.string.diagnostics_section_engine)) {
                DiagRow(
                    stringResource(R.string.diagnostics_native_engine),
                    stringResource(
                        if (state.nativeEngineAvailable) R.string.diagnostics_loaded
                        else R.string.diagnostics_unavailable
                    ),
                )
                DiagRow(
                    stringResource(R.string.diagnostics_landmarks),
                    state.landmarkCount.toString(),
                )
                DiagRow(
                    stringResource(R.string.diagnostics_scale_confidence),
                    "%.2f".format(state.scaleConfidence),
                )
            }
            SettingsDivider()
            SettingsSection(title = stringResource(R.string.diagnostics_section_tracking)) {
                DiagRow(
                    stringResource(R.string.diagnostics_features_detected),
                    state.featureCount.toString(),
                )
                DiagRow(
                    stringResource(R.string.diagnostics_features_tracked),
                    state.trackedCount.toString(),
                )
                DiagRow(
                    stringResource(R.string.diagnostics_frame_rate),
                    "%.1f Hz".format(state.frameRateHz),
                )
            }
            SettingsDivider()
            SettingsSection(title = stringResource(R.string.diagnostics_section_sensors)) {
                DiagRow(
                    stringResource(R.string.diagnostics_imu_rate),
                    "%.0f Hz".format(state.imuRateHz),
                )
                DiagRow(
                    stringResource(R.string.diagnostics_clock_skew),
                    "%.1f ms".format(state.timestampSkewMs),
                )
            }
            SettingsDivider()
            SettingsSection(title = stringResource(R.string.diagnostics_section_intrinsics)) {
                DiagRow(
                    stringResource(R.string.diagnostics_focal_length),
                    "%.1f px".format(state.focalPx),
                )
                DiagRow(
                    stringResource(R.string.diagnostics_principal_point),
                    "%.1f, %.1f".format(state.principalPointPx.first, state.principalPointPx.second),
                )
            }
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.diagnostics_reset_tracking),
                onClick = actions::resetTracking,
            )
        }
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

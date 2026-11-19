package com.vayunmathur.setupwizard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.IconLocationOn
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SetupAction
import com.vayunmathur.library.ui.SetupScaffold
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.setupwizard.R
import com.vayunmathur.setupwizard.platform.SetupUiState

/**
 * Location services.
 *
 * Wi-Fi scanning is a device-wide setting, so a secondary user is not offered it - they would
 * be changing it for everyone. Network location and geocoding are not offered at all: Modern
 * Apps OS pins one on-device provider that serves both, so there is nothing to choose.
 */
@Composable
fun LocationScreen(
    state: SetupUiState,
    onLocationEnabled: (Boolean) -> Unit,
    onWifiScanningEnabled: (Boolean) -> Unit,
    onNext: () -> Unit,
) {
    SetupScaffold(
        title = stringResource(R.string.location_services),
        icon = { IconLocationOn() },
        primaryAction = SetupAction(stringResource(R.string.next), onNext),
    ) {
        SwitchRow(
            title = stringResource(R.string.location_access_title),
            summary = stringResource(R.string.location_access_desc),
            checked = state.locationEnabled,
            onCheckedChange = onLocationEnabled,
        )
        if (state.isPrimaryUser) {
            SwitchRow(
                title = stringResource(R.string.wifi_scanning_always_available_enabled_title),
                summary = stringResource(R.string.wifi_scanning_always_available_enabled_desc),
                checked = state.wifiScanningEnabled,
                // Scanning with Wi-Fi off only means anything while location is on, so the
                // row follows it rather than sitting there doing nothing.
                enabled = state.locationEnabled,
                onCheckedChange = onWifiScanningEnabled,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

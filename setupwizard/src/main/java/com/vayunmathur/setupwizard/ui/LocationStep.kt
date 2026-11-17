package com.vayunmathur.setupwizard.ui

import androidx.compose.runtime.Composable
import com.vayunmathur.setupwizard.platform.SetupUiState
import com.vayunmathur.setupwizard.platform.SetupViewModel

@Composable
internal fun LocationStep(
    viewModel: SetupViewModel,
    state: SetupUiState,
    onNext: () -> Unit,
) {
    LocationScreen(
        state = state,
        onLocationEnabled = viewModel::setLocationEnabled,
        onWifiScanningEnabled = viewModel::setWifiScanningEnabled,
        onNext = onNext,
    )
}

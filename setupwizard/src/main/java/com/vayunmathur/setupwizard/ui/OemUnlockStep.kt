package com.vayunmathur.setupwizard.ui

import androidx.compose.runtime.Composable
import com.vayunmathur.setupwizard.platform.SetupUiState
import com.vayunmathur.setupwizard.platform.SetupViewModel

@Composable
internal fun OemUnlockStep(
    viewModel: SetupViewModel,
    state: SetupUiState,
    onContinue: () -> Unit,
) {
    OemUnlockScreen(
        state = state,
        onStartAckTimer = viewModel::startBootloaderAckTimer,
        onRebootToBootloader = viewModel::rebootToBootloader,
        // Not a step of its own, so continuing resumes where the welcome step would
        // have gone rather than looking for whatever follows this screen.
        onContinue = onContinue,
    )
}

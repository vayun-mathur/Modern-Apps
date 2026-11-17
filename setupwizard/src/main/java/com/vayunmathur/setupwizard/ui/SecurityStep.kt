package com.vayunmathur.setupwizard.ui

import androidx.compose.runtime.Composable
import com.vayunmathur.setupwizard.platform.SetupIntents
import com.vayunmathur.setupwizard.platform.SetupUiState
import com.vayunmathur.setupwizard.platform.SetupViewModel

@Composable
internal fun SecurityStep(
    viewModel: SetupViewModel,
    state: SetupUiState,
    onCancelled: () -> Unit,
    onDone: () -> Unit,
) {
    HandoffScreen(
        // A device that already has a lock screen has nothing to enrol, so the step
        // reports itself unavailable rather than opening an empty enrolment flow.
        intent = { if (state.deviceSecure) null else SetupIntents.biometricEnroll() },
        onCancelled = { viewModel.refreshSecurity(); onCancelled() },
        onCompleted = { viewModel.refreshSecurity(); onDone() },
        onUnavailable = { onDone() },
    )
}

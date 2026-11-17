package com.vayunmathur.setupwizard.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import com.vayunmathur.setupwizard.platform.SetupUiState
import com.vayunmathur.setupwizard.platform.SetupViewModel

@Composable
internal fun FinishStep(
    viewModel: SetupViewModel,
    state: SetupUiState,
    activity: Activity?,
) {
    FinishScreen(
        state = state,
        onFinish = { disableOemUnlocking ->
            activity?.let { viewModel.finishSetup(it, disableOemUnlocking) }
        },
    )
}

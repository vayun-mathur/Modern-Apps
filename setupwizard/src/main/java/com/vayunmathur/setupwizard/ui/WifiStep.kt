package com.vayunmathur.setupwizard.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.setupwizard.R
import com.vayunmathur.setupwizard.platform.SetupIntents

@Composable
internal fun WifiStep(onCancelled: () -> Unit, onDone: () -> Unit) {
    val title = stringResource(R.string.connect_to_wi_fi)
    val description = stringResource(R.string.select_a_network)
    val skip = stringResource(R.string.set_up_without_wi_fi)
    HandoffScreen(
        intent = { SetupIntents.setupInternet(title, description, skip) },
        onCancelled = onCancelled,
        onCompleted = { onDone() },
        onUnavailable = { onDone() },
    )
}

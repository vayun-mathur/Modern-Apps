package com.vayunmathur.setupwizard.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.IconCheckCircle
import com.vayunmathur.library.ui.SetupAction
import com.vayunmathur.library.ui.SetupScaffold
import com.vayunmathur.setupwizard.R
import com.vayunmathur.setupwizard.platform.SetupUiState
import com.vayunmathur.setupwizard.ui.components.SetupCheckboxRow

/**
 * The last step, and the only place the wizard writes anything irreversible.
 *
 * The OEM-unlocking checkbox is shown only when there is something to turn off: the bootloader
 * has to be locked, the user has to be the device owner, and the "OEM unlocking" toggle has to
 * currently be on. When it is hidden its value is not read either, so a stale tick from a
 * previous composition cannot leak into [onFinish].
 */
@Composable
fun FinishScreen(state: SetupUiState, onFinish: (disableOemUnlocking: Boolean) -> Unit) {
    var disableOemUnlocking by remember { mutableStateOf(state.disableOemUnlockingChecked) }
    val offerOemUnlockingOptOut = state.disableOemUnlockingVisible && state.oemUnlockingEnabled

    SetupScaffold(
        title = stringResource(R.string.you_re_all_set_now),
        subtitle = stringResource(
            if (state.isPrimaryUser) R.string.device_setup_done_desc
            else R.string.profile_setup_done_desc
        ),
        icon = { IconCheckCircle() },
        primaryAction = SetupAction(
            label = stringResource(R.string.start),
            onClick = { onFinish(offerOemUnlockingOptOut && disableOemUnlocking) },
        ),
    ) {
        if (offerOemUnlockingOptOut) {
            SetupCheckboxRow(
                checked = disableOemUnlocking,
                onCheckedChange = { disableOemUnlocking = it },
                label = stringResource(R.string.disable_oem_unlocking),
                description = stringResource(R.string.disable_oem_unlocking_desc),
            )
        }
    }
}

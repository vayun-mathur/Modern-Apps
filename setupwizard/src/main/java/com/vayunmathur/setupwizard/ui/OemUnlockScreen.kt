package com.vayunmathur.setupwizard.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.IconWarning
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SetupAction
import com.vayunmathur.library.ui.SetupScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.setupwizard.R
import com.vayunmathur.setupwizard.platform.SetupUiState
import com.vayunmathur.setupwizard.ui.components.SetupCheckboxRow

/**
 * The unlocked-bootloader warning in full, reached from the welcome step instead of the next
 * step when the bootloader is unlocked.
 *
 * Continuing is gated twice: a countdown that has to run out, and a box that has to be ticked.
 * That is deliberate friction - locking the bootloader later wipes the device, so this is the
 * last cheap moment to do it, and the screen is designed to be read rather than dismissed.
 */
@Composable
fun OemUnlockScreen(
    state: SetupUiState,
    onStartAckTimer: () -> Unit,
    onRebootToBootloader: () -> Unit,
    onContinue: () -> Unit,
) {
    var acknowledged by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { onStartAckTimer() }

    val secondsLeft = state.bootloaderAckSeconds
    val continueLabel = if (secondsLeft == 0) {
        stringResource(R.string.continue_without_locking)
    } else {
        stringResource(R.string.continue_without_locking_timer, secondsLeft)
    }

    SetupScaffold(
        title = stringResource(R.string.lock_your_bootloader),
        icon = { IconWarning(tint = MaterialTheme.colorScheme.error) },
        primaryAction = SetupAction(
            stringResource(R.string.reboot_to_bootloader),
            onRebootToBootloader,
        ),
        secondaryAction = SetupAction(
            label = continueLabel,
            onClick = onContinue,
            enabled = secondsLeft == 0 && acknowledged,
        ),
    ) {
        Text(
            stringResource(R.string.oem_unlock_fullscreen_desc),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.oem_lock_url),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        SetupCheckboxRow(
            checked = acknowledged,
            onCheckedChange = { acknowledged = it },
            label = stringResource(R.string.oem_unlock_ack_risks),
        )
    }
}

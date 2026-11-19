package com.vayunmathur.euicc.ui.download

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.euicc.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.IconSim
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SetupAction
import com.vayunmathur.library.ui.SetupScaffold
import com.vayunmathur.library.ui.Text

/**
 * "Confirm your network" - the SM-DP+ has said which profile the activation code resolves
 * to, and the user accepts it before anything is written to the eUICC.
 *
 * This is the last point where backing out costs nothing. Not reachable until the native
 * core parses ProfileMetadata out of the AuthenticateClient response; see
 * [com.vayunmathur.euicc.platform.DownloadState.Confirm].
 */
@Composable
fun ConfirmCarrierContent(
    carrier: String?,
    profileName: String?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    SetupScaffold(
        title = stringResource(R.string.carrier_confirm_title),
        subtitle = stringResource(R.string.carrier_confirm_subtitle),
        icon = { IconSim() },
        primaryAction = SetupAction(stringResource(R.string.download_esim_button), onConfirm),
        secondaryAction = SetupAction(stringResource(UiR.string.cancel), onCancel),
    ) {
        if (carrier != null) {
            Text(carrier, style = MaterialTheme.typography.titleLarge)
        }
        if (profileName != null && profileName != carrier) {
            Text(
                profileName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

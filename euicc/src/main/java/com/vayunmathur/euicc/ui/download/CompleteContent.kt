package com.vayunmathur.euicc.ui.download

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.euicc.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.IconCheckCircle
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SetupAction
import com.vayunmathur.library.ui.SetupScaffold
import com.vayunmathur.library.ui.Text

/**
 * The profile is installed but not enabled.
 *
 * The wording is the platform LPA's, and it is deliberately not a claim that the eSIM is
 * working: SGP.22 install and enable are separate operations, and enabling is the system's
 * decision because it is the thing that has to disconnect whatever is currently connected.
 */
@Composable
fun CompleteContent(carrier: String?, onDone: () -> Unit) {
    SetupScaffold(
        title = stringResource(R.string.download_complete_title),
        subtitle = stringResource(R.string.download_complete_text),
        icon = { IconCheckCircle() },
        primaryAction = SetupAction(stringResource(UiR.string.done), onDone),
    ) {
        if (carrier != null) {
            Text(carrier, style = MaterialTheme.typography.titleMedium)
        }
    }
}

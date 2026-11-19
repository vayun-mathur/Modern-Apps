package com.vayunmathur.euicc.ui.download

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.euicc.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.IconWarning
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SetupAction
import com.vayunmathur.library.ui.SetupScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior

/**
 * The download failed.
 *
 * [message] is whatever the SM-DP+ or the native core said, shown as the step's own
 * content rather than only the subtitle so the generic line and the specific cause
 * read as separate blocks — the message is usually the only clue about which of the
 * many things in an SGP.22 download went wrong.
 */
@Composable
fun FailedContent(message: String?, onRetry: () -> Unit, onCancel: () -> Unit) {
    SetupScaffold(
        title = stringResource(R.string.download_fail_dialog_title),
        subtitle = stringResource(R.string.download_fail_generic),
        icon = { IconWarning() },
        primaryAction = SetupAction(stringResource(R.string.try_again), onRetry),
        secondaryAction = SetupAction(stringResource(UiR.string.cancel), onCancel),
    ) {
        if (message != null) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

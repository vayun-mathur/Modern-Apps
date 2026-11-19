package com.vayunmathur.euicc.ui.download

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.vayunmathur.euicc.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.IconSim
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.SetupAction
import com.vayunmathur.library.ui.SetupScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberMessenger

/**
 * Some profiles are confirmation-code protected: the carrier issues a second secret
 * alongside the activation code, and the eUICC refuses PrepareDownload without it.
 *
 * Not reachable until the native core threads the code into PrepareDownload - today it
 * always passes none, so such profiles simply fail. See
 * [com.vayunmathur.euicc.platform.DownloadState.AwaitingConfirmationCode].
 */
@Composable
fun ConfirmationCodeContent(
    carrier: String?,
    error: Boolean,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    val messenger = rememberMessenger()
    val wrongCodeMessage = stringResource(R.string.confirmation_code_wrong)
    SetupScaffold(
        title = stringResource(R.string.confirmation_code_title),
        subtitle = carrier?.let { stringResource(R.string.confirmation_code_text, it) }
            ?: stringResource(R.string.confirmation_code_text_no_carrier),
        icon = { IconSim() },
        primaryAction = SetupAction(
            label = stringResource(UiR.string.continue_label),
            onClick = {
                if (error) messenger.show(wrongCodeMessage)
                onSubmit(code.trim())
            },
            enabled = code.isNotBlank(),
        ),
        secondaryAction = SetupAction(stringResource(UiR.string.cancel), onCancel),
    ) {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text(stringResource(R.string.confirmation_code_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            isError = error,
            supportingText = if (error) {
                { Text(stringResource(R.string.confirmation_code_wrong)) }
            } else {
                null
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

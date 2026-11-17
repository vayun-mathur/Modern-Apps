package com.vayunmathur.pdf.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.pdf.R

/** Password gate for encrypted PDFs. */
@Composable
internal fun SafePdfPasswordPrompt(
    pwInput: String,
    onPwInput: (String) -> Unit,
    pwError: Boolean,
    onOpen: () -> Unit,
    onBack: () -> Unit,
) {
    com.vayunmathur.library.ui.AlertDialog(
        onDismissRequest = { onBack() },
        title = { Text(stringResource(R.string.password_required)) },
        text = {
            Column {
                if (pwError) Text(stringResource(R.string.incorrect_password), color = MaterialTheme.colorScheme.error)
                TextField(
                    value = pwInput,
                    onValueChange = onPwInput,
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    placeholder = { Text(stringResource(R.string.password)) },
                )
            }
        },
        confirmButton = { TextButton(onOpen) { Text(stringResource(R.string.open)) } },
        dismissButton = { TextButton({ onBack() }) { Text(stringResource(UiR.string.cancel)) } },
    )
}

/** Note / callout / style / encrypt dialogs driven by [SafePdfViewerState]. */
@Composable
internal fun SafePdfPendingDialogs(
    state: SafePdfViewerState,
    document: com.vayunmathur.pdf.platform.SafePdfDocument?,
    launchers: SafePdfLauncherSet,
) {
    state.pendingNote?.let { (page, pt) ->
        var noteText by remember { mutableStateOf("") }
        com.vayunmathur.library.ui.AlertDialog(
            onDismissRequest = { state.pendingNote = null },
            title = { Text(stringResource(R.string.sticky_note)) },
            text = {
                TextField(value = noteText, onValueChange = { noteText = it }, placeholder = { Text(stringResource(R.string.note_text)) })
            },
            confirmButton = {
                TextButton({
                    state.createNote(document, page, pt, noteText)
                }) { Text(stringResource(UiR.string.add)) }
            },
            dismissButton = { TextButton({ state.pendingNote = null }) { Text(stringResource(UiR.string.cancel)) } },
        )
    }

    state.pendingCallout?.let { (page, a, b) ->
        var calloutText by remember { mutableStateOf("Text") }
        com.vayunmathur.library.ui.AlertDialog(
            onDismissRequest = { state.pendingCallout = null },
            title = { Text(stringResource(R.string.callout)) },
            text = { TextField(value = calloutText, onValueChange = { calloutText = it }) },
            confirmButton = {
                TextButton({
                    state.createCallout(document, page, a, b, calloutText)
                }) { Text(stringResource(UiR.string.add)) }
            },
            dismissButton = { TextButton({ state.pendingCallout = null }) { Text(stringResource(UiR.string.cancel)) } },
        )
    }

    if (state.showStyle) {
        StyleDialog(
            color = state.color,
            onColor = { state.color = it },
            opacity = state.opacity,
            onOpacity = { state.opacity = it },
            strokeWidth = state.strokeWidth,
            onWidth = { state.strokeWidth = it },
            onDismiss = { state.showStyle = false },
        )
    }

    if (state.showEncrypt) {
        var pw by remember { mutableStateOf("") }
        com.vayunmathur.library.ui.AlertDialog(
            onDismissRequest = { state.showEncrypt = false },
            title = { Text(stringResource(R.string.encrypt_with_password)) },
            text = {
                TextField(
                    value = pw,
                    onValueChange = { pw = it },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    placeholder = { Text(stringResource(R.string.password)) },
                )
            },
            confirmButton = {
                TextButton({
                    state.showEncrypt = false
                    if (pw.isNotEmpty()) {
                        state.pendingEncryptPw = pw
                        launchers.saveEncrypted()
                    }
                }) { Text(stringResource(R.string.save_encrypted)) }
            },
            dismissButton = { TextButton({ state.showEncrypt = false }) { Text(stringResource(UiR.string.cancel)) } },
        )
    }
}

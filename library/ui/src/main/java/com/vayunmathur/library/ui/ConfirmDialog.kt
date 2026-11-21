package com.vayunmathur.library.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics

/**
 * The yes/no dialog, mostly for destructive actions.
 *
 * Twenty-four apps reached for [AlertDialog] directly and each made its own
 * call about button order, whether the destructive option was coloured, and
 * whether it was the confirm or the dismiss button. Delete confirmations in
 * particular were rewritten nearly every time one was needed.
 *
 * Set [destructive] for anything that loses data: it colours the confirm
 * button with the error colour, which is the one visual cue that reliably
 * makes someone read the dialog. Dialogs that collect input rather than a
 * yes/no answer should still use [AlertDialog] directly.
 */
@Composable
fun ConfirmDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    message: String? = null,
    dismissLabel: String? = null,
    destructive: Boolean = false,
    // For the rare confirm label that needs its own styling; when null the
    // label is rendered from confirmLabel with the standard treatment.
    confirmContent: (@Composable () -> Unit)? = null,
) {
    // Initial focus (the FocusRequester pattern from GameResultOverlay):
    // paneTitle makes TalkBack announce this title, and keyboard/D-pad focus
    // lands on the dismiss button when there is one — the safe choice for a
    // destructive dialog — otherwise on the confirm button. Requesting in
    // onGloballyPositioned, not LaunchedEffect: a Dialog popup is not yet
    // laid out when effects first run, so an effect request is lost. The
    // shared AlertDialog wrapper applies the same placed-request to its
    // surface for dialogs without a known safe button.
    val initialFocus = remember { FocusRequester() }
    var focusRequested by remember { mutableStateOf(false) }
    val initialFocusModifier = Modifier
        .focusRequester(initialFocus)
        .onGloballyPositioned {
            if (!focusRequested) {
                focusRequested = true
                runCatching { initialFocus.requestFocus() }
            }
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.semantics { paneTitle = title },
        title = { Text(title) },
        text = message?.let { { Text(it) } },
        confirmButton = {
            TextButton(
                onClick = {
                    // Dismiss first so the dialog cannot linger over whatever the
                    // action navigates to.
                    onDismiss()
                    onConfirm()
                },
                modifier = if (dismissLabel == null) initialFocusModifier else Modifier,
            ) {
                if (confirmContent != null) confirmContent()
                else Text(
                    confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = dismissLabel?.let {
            {
                TextButton(onClick = onDismiss, modifier = initialFocusModifier) { Text(it) }
            }
        },
    )
}

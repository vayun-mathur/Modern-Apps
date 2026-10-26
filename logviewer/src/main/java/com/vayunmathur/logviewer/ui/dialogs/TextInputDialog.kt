package com.vayunmathur.logviewer.ui.dialogs

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.logviewer.R
import com.vayunmathur.library.ui.R as UiR

/**
 * A dialog that collects one piece of text: the filter regex.
 *
 * Replaces the hand-built `EditorDialog` (an `EditText` inside a `FrameLayout` inside an
 * `AlertDialog`), which existed because there is no framework dialog for this. The 10,000-character
 * cap is kept: the field is a note on a log, and the log is already the large part.
 *
 * [singleLine] also turns the IME's action key into "done", so a short filter can be applied
 * without reaching for the button.
 */
@Composable
internal fun TextInputDialog(
    title: String,
    initialText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    singleLine: Boolean = false,
    hint: String? = null,
) {
    var text by rememberSaveable(initialText) { mutableStateOf(initialText) }
    val focusRequester = remember { FocusRequester() }

    // The keyboard is up as the dialog opens: it exists to be typed in.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= MAX_LENGTH) text = it },
                placeholder = hint?.let { { Text(it) } },
                singleLine = singleLine,
                keyboardOptions = KeyboardOptions(
                    imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
                ),
                keyboardActions = KeyboardActions(onDone = { onConfirm(text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text(stringResource(UiR.string.apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) }
        },
    )
}

private const val MAX_LENGTH = 10_000

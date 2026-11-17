package com.vayunmathur.office.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

import com.vayunmathur.office.R
/** Prompts the user to opt into online sharing before the first share (which generates keys + id). */
@Composable
fun EnableOnlineDialog(onEnable: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.enable_online_sharing)) },
        text = { Text(stringResource(R.string.sharing_online_generates_your_encryption)) },
        confirmButton = { TextButton(onClick = onEnable) { Text(stringResource(R.string.enable)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) } }
    )
}

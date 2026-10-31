package com.vayunmathur.todo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TimePicker
import com.vayunmathur.library.ui.rememberIs24Hour
import com.vayunmathur.library.ui.rememberTimePickerState
import com.vayunmathur.todo.R
import kotlinx.datetime.LocalTime
import com.vayunmathur.library.ui.R as UiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoTimePickerDialog(initial: LocalTime?, onConfirm: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val start = initial ?: LocalTime(DEFAULT_HOUR, 0)
    val state = rememberTimePickerState(initialHour = start.hour, initialMinute = start.minute, is24Hour = rememberIs24Hour())
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime(state.hour, state.minute)) }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) } },
    )
}

private const val DEFAULT_HOUR = 9

package com.vayunmathur.todo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DatePicker
import com.vayunmathur.library.ui.DatePickerDialog
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.rememberDatePickerState
import com.vayunmathur.todo.R
import com.vayunmathur.todo.domain.DueDates
import kotlinx.datetime.TimeZone
import com.vayunmathur.library.ui.R as UiR

/**
 * Date picker speaking local-midnight epoch ms. The Material picker works in UTC midnight;
 * the conversion lives in [DueDates] so callers never see that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoDatePickerDialog(initial: Long?, onConfirm: (Long) -> Unit, onDismiss: () -> Unit) {
    val zone = TimeZone.currentSystemDefault()
    val state = rememberDatePickerState(initialSelectedDateMillis = initial?.let { DueDates.toPickerMillis(it, zone) })
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = { state.selectedDateMillis?.let { onConfirm(DueDates.fromPickerMillis(it, zone)) } },
                enabled = state.selectedDateMillis != null,
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) } },
    ) {
        DatePicker(state)
    }
}

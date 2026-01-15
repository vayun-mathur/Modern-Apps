package com.vayunmathur.contacts.ui.dialog

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.LocalNavResultRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

@Composable
fun EventDatePickerDialog(id: String, initialDate: LocalDate?, onDismiss: () -> Unit) {
    val registry = LocalNavResultRegistry.current
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = (initialDate?: Clock.System.now().toLocalDateTime(
            TimeZone.currentSystemDefault()).date).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
    )
    DatePickerDialog(
        onDismissRequest = { onDismiss() },
        confirmButton = {
            TextButton(onClick = {
                datePickerState.selectedDateMillis?.let {
                    val result = Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date
                    CoroutineScope(Dispatchers.Main).launch {
                        registry.dispatchResult(id, result)
                    }
                }
                onDismiss()
            }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }) {
                Text("Cancel")
            }
        }
    ) {
        DatePicker(state = datePickerState)
    }
}
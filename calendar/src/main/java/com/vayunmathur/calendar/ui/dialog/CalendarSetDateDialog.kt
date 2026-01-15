package com.vayunmathur.calendar.ui.dialog

import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.calendar.Route
import com.vayunmathur.library.util.pop
import kotlin.time.Instant
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.atStartOfDayIn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarSetDateDialog(backStack: NavBackStack<Route>, dateViewingEpochDays: kotlinx.datetime.LocalDate) {
    val registry = LocalNavResultRegistry.current
    val state = rememberDatePickerState(dateViewingEpochDays.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds())
    val scope = rememberCoroutineScope()
    DatePickerDialog(
        onDismissRequest = { backStack.pop() },
        confirmButton = {
            Button(onClick = {
                val result = Instant.fromEpochMilliseconds(state.selectedDateMillis!!)
                    .toLocalDateTime(TimeZone.UTC).date
                scope.launch { registry.dispatchResult("GotoDate", result) }
                backStack.pop()
            }, enabled = state.selectedDateMillis != null) {
                Text("Go to date")
            }
        },
        dismissButton = {
            Button(onClick = { backStack.pop() }) { Text("Cancel") }
        }
    ) {
        DatePicker(state)
    }
}

package com.vayunmathur.calendar.ui.dialog

import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.calendar.Route
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.pop
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerDialog(backStack: NavBackStack<Route>, resultKey: String, initialDate: LocalDate, minDate: LocalDate? = null) {
    val registry = LocalNavResultRegistry.current
    val state = rememberDatePickerState(initialDate.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
        selectableDates = object: SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                if(minDate == null) return true
                return Instant.fromEpochMilliseconds(utcTimeMillis).toLocalDateTime(TimeZone.UTC).date >= minDate
            }

            override fun isSelectableYear(year: Int): Boolean {
                if(minDate == null) return true
                return year >= minDate.year
            }
        })
    val scope = rememberCoroutineScope()
    DatePickerDialog(
        onDismissRequest = { backStack.pop() },
        confirmButton = {
            Button(onClick = {
                val selectedMs = state.selectedDateMillis!!
                val result = Instant.fromEpochMilliseconds(selectedMs)
                    .toLocalDateTime(TimeZone.UTC).date
                scope.launch { registry.dispatchResult(resultKey, result) }
                backStack.pop()
            }, enabled = state.selectedDateMillis != null) {
                Text("OK")
            }
        },
        dismissButton = {
            Button(onClick = { backStack.pop() }) { Text("Cancel") }
        }
    ) {
        DatePicker(state)
    }
}

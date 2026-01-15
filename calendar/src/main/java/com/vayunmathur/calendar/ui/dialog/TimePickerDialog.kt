package com.vayunmathur.calendar.ui.dialog

import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.calendar.Route
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.pop
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialogContent(backStack: NavBackStack<Route>, resultKey: String, initialTime: LocalTime, minTime: LocalTime? = null) {
    val registry = LocalNavResultRegistry.current
    val state = rememberTimePickerState(initialTime.hour, initialTime.minute)
    val selectedTime = LocalTime(state.hour, state.minute)
    val scope = rememberCoroutineScope()
    TimePickerDialog(
        onDismissRequest = { backStack.pop() },
        title = { Text("Select time") },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch { registry.dispatchResult(resultKey, selectedTime) }
                    backStack.pop()
                },
                enabled = (minTime == null || selectedTime >= minTime)
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            Button(onClick = { backStack.pop() }) { Text("Cancel") }
        }
    ) {
        TimePicker(state)
    }
}


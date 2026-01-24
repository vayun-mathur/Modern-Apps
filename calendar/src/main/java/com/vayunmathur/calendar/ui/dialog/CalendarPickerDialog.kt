package com.vayunmathur.calendar.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.calendar.ContactViewModel
import com.vayunmathur.calendar.Route
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.pop
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarPickerDialog(backStack: NavBackStack<Route>, resultKey: String) {
    val registry = LocalNavResultRegistry.current
    val scope = rememberCoroutineScope()
    val vm: ContactViewModel = viewModel()
    val calendars = vm.calendars.collectAsState().value

    // group calendars by accountName and filter editable ones (canModify)
    val editable = calendars.filter { it.canModify }
    val grouped = editable.groupBy { it.accountName.ifEmpty { "(Local)" } }

    AlertDialog(
        onDismissRequest = { backStack.pop() },
        title = { Text("Choose calendar") },
        text = {
            // scrollable list of calendars grouped by account
            LazyColumn(modifier = Modifier.height(320.dp)) {
                grouped.forEach { (account, list) ->
                    item {
                        Text(account, modifier = Modifier.padding(8.dp))
                    }
                    items(list) { cal ->
                        Row(modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch { registry.dispatchResult(resultKey, cal.id) }
                                backStack.pop()
                            }
                            .padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier
                                .size(16.dp)
                                .background(Color(cal.color)))
                            Text(cal.displayName, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { backStack.pop() }) { Text("Close") }
        }
    )
}


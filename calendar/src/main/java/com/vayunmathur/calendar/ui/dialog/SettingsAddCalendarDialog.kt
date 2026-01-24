package com.vayunmathur.calendar.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.calendar.ContactViewModel
import com.vayunmathur.calendar.Route
import com.vayunmathur.library.util.pop

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAddCalendarDialog(viewModel: ContactViewModel, backStack: NavBackStack<Route>) {
    var newDisplayName by remember { mutableStateOf("") }
    var newColor by remember { mutableIntStateOf(0xFF2196F3.toInt()) }

    val swatches = listOf(
        0xFFF44336.toInt(), // red
        0xFFE91E63.toInt(), // pink
        0xFF9C27B0.toInt(), // purple
        0xFF3F51B5.toInt(), // indigo
        0xFF2196F3.toInt(), // blue
        0xFF009688.toInt(), // teal
        0xFF4CAF50.toInt(), // green
        0xFFFFC107.toInt(), // amber
        0xFFFF9800.toInt(), // orange
        0xFF795548.toInt(), // brown
        0xFF607D8B.toInt()  // blue grey
    )

    AlertDialog(
        onDismissRequest = { backStack.pop() },
        title = { Text(text = "New local calendar") },
        text = {
            Column {
                OutlinedTextField(
                    value = newDisplayName,
                    onValueChange = { newDisplayName = it },
                    label = { Text("Calendar name") },
                    modifier = Modifier
                        .padding(0.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text("Choose color:")
                LazyRow {
                    items(swatches) { c ->
                        val selected = (newColor == c)
                        Box(
                            modifier = Modifier
                                .padding(6.dp)
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) Color.Black else Color.Black.copy(alpha = 0.12f),
                                    shape = CircleShape
                                )
                                .clickable { newColor = c }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = newDisplayName.isNotBlank() && newColor != 0, onClick = {
                val accessLevel = android.provider.CalendarContract.Calendars.CAL_ACCESS_EDITOR
                viewModel.createLocalCalendar("Offline Calendar", newDisplayName.ifEmpty { "New Calendar" }, newColor, true, accessLevel)
                backStack.pop()
            }) { Text("Create") }
        },
        dismissButton = {
            Button(onClick = { backStack.pop() }) { Text("Cancel") }
        }
    )
}

package com.vayunmathur.calendar.ui.dialogs
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
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.calendar.platform.CalendarViewModel
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.Route

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAddCalendarDialog(viewModel: CalendarViewModel, backStack: NavBackStack<Route>) {
    var newDisplayName by remember { mutableStateOf("") }
    var newColor by remember { mutableIntStateOf(0xFF2196F3.toInt()) }

    AlertDialog(
        onDismissRequest = { backStack.pop() },
        title = { Text(text = stringResource(R.string.new_local_calendar)) },
        text = {
            Column {
                OutlinedTextField(
                    value = newDisplayName,
                    onValueChange = { newDisplayName = it },
                    label = { Text(stringResource(R.string.calendar_name)) },
                    modifier = Modifier
                        .padding(0.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.choose_color))
                LazyRow {
                    items(COLOR_SWATCHES, key = { it }) { c ->
                        val selected = (newColor == c)
                        Box(
                            modifier = Modifier
                                .padding(6.dp)
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
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
            }) { Text(stringResource(UiR.string.create)) }
        },
        dismissButton = {
            Button(onClick = { backStack.pop() }) { Text(stringResource(UiR.string.cancel)) }
        }
    )
}

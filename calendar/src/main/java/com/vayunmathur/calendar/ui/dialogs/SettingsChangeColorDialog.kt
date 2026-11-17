package com.vayunmathur.calendar.ui.dialogs
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

val COLOR_SWATCHES = listOf(
    0xFFF44336.toInt(),
    0xFFE91E63.toInt(),
    0xFF9C27B0.toInt(),
    0xFF3F51B5.toInt(),
    0xFF2196F3.toInt(),
    0xFF009688.toInt(),
    0xFF4CAF50.toInt(),
    0xFFFFC107.toInt(),
    0xFFFF9800.toInt(),
    0xFF795548.toInt(),
    0xFF607D8B.toInt()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsChangeColorDialog(viewModel: CalendarViewModel, backStack: NavBackStack<Route>, calendarId: Long) {
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()
    val cal = calendars.find { it.id == calendarId } ?: run {
        backStack.pop()
        return
    }
    var tempColor by remember { mutableIntStateOf(cal.color) }

    AlertDialog(
        onDismissRequest = { backStack.pop() },
        title = { Text(stringResource(R.string.change_color_for)) },
        text = {
            Column {
                // swatches row
                LazyRow {
                    items(COLOR_SWATCHES, key = { it }) { c ->
                        val selected = (tempColor == c)
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
                                .clickable {
                                    tempColor = c
                                }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                // persist color change via ViewModel
                viewModel.setCalendarColor(cal.id, tempColor)
                backStack.pop()
            }) {
                Text(stringResource(R.string.change_color))
            }
        },
        dismissButton = {
            Button(onClick = { backStack.pop() }) {
                Text(stringResource(UiR.string.cancel))
            }
        }
    )
}

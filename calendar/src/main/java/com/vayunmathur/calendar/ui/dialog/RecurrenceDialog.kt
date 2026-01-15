package com.vayunmathur.calendar.ui.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.calendar.RRule
import com.vayunmathur.calendar.RecurrenceParams
import com.vayunmathur.calendar.Route
import com.vayunmathur.calendar.ui.dateFormat
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.ResultEffect
import com.vayunmathur.library.util.pop
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurrenceDialog(backStack: NavBackStack<Route>, resultKey: String, startDate: LocalDate, initial: RecurrenceParams?) {
    val registry = LocalNavResultRegistry.current
    val scope = rememberCoroutineScope()

    var freq by remember { mutableStateOf(initial?.freq ?: "days") }
    var intervalStr by remember { mutableStateOf((initial?.interval ?: 1).toString()) }
    var monthlyType by remember { mutableStateOf(initial?.monthlyType ?: 0) }
    var daysOfWeek by remember { mutableStateOf(initial?.daysOfWeek ?: emptyList()) }
    var endCondition by remember { mutableStateOf(initial?.endCondition ?: RRule.EndCondition.Never) }

    // result key for the nested date picker used for UNTIL
    val KEY_UNTIL = "$resultKey.until"
    // listen for date picker result
    ResultEffect<LocalDate>(KEY_UNTIL) { selected ->
        endCondition = RRule.EndCondition.Until(selected)
    }

    AlertDialog(
        onDismissRequest = { backStack.pop() },
        confirmButton = {
            Button(onClick = {
                val params = if (freq == "NONE") null else RecurrenceParams(
                    freq = freq,
                    interval = intervalStr.toIntOrNull() ?: 1,
                    daysOfWeek = daysOfWeek,
                    monthlyType = monthlyType,
                    endCondition = endCondition
                )

                val rrule = params?.let { p ->
                    when (p.freq) {
                        "days" -> RRule.EveryXDays(p.interval, p.endCondition)
                        "weeks" -> RRule.EveryXWeeks(p.interval, p.daysOfWeek, p.endCondition)
                        "months" -> RRule.EveryXMonths(p.interval, p.monthlyType, p.endCondition)
                        "years" -> RRule.EveryXYears(p.interval, p.endCondition)
                        else -> null
                    }
                } ?: ""

                scope.launch { registry.dispatchResult(resultKey, rrule) }
                backStack.pop()
            }) { Text("OK") }
        },
        dismissButton = {
            Button(onClick = { backStack.pop() }) { Text("Cancel") }
        },
        text = {
            Column(Modifier.padding(8.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text("Repeat   ")
                    var openDropdown by remember { mutableStateOf(false) }

                    OutlinedTextField(
                        intervalStr,
                        { intervalStr = it },
                        leadingIcon = {Text("  Every")},
                        trailingIcon = {
                            Text("$freq ▼  ", Modifier.clickable{
                                openDropdown = true
                            })
                            DropdownMenu(openDropdown, onDismissRequest = { openDropdown = false }) {
                                listOf("days", "weeks", "months", "years").forEach { f ->
                                    DropdownMenuItem({ Text(f) }, onClick = {
                                        freq = f
                                        openDropdown = false
                                    })
                                }
                            }
                        }
                    )
                }

                if (freq == "weeks") {
                    Text("On days of week")
                    val dayOfWeekCircle = @Composable { d: DayOfWeek ->
                        Surface(Modifier.clickable {
                            daysOfWeek = if (daysOfWeek.contains(d)) daysOfWeek - d else daysOfWeek + d
                        }, color = if(d in daysOfWeek) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                            shape = CircleShape) {
                            Box(Modifier.size(50.dp), contentAlignment = Alignment.Center) {
                                Text(d.name.take(3).capitalcase())
                            }
                        }
                    }
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DayOfWeek.entries.take(4).forEach {
                                dayOfWeekCircle(it)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DayOfWeek.entries.drop(4).forEach {
                                dayOfWeekCircle(it)
                            }
                        }
                    }
                }

                if (freq == "months") {
                    Text("Monthly type")
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(monthlyType == 0, {monthlyType = 0}, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                            Text(ordinal(startDate.day))
                        }
                        SegmentedButton(monthlyType == 1, {monthlyType = 1}, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                            Text("${ordinal((startDate.day-1)/7+1)} ${startDate.dayOfWeek.name.take(3).capitalcase()}")
                        }
                    }
                }

                Text("End")

                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(endCondition is RRule.EndCondition.Never, {endCondition = RRule.EndCondition.Never}, shape = SegmentedButtonDefaults.itemShape(0, 3)) {
                        Text("Never")
                    }
                    SegmentedButton(endCondition is RRule.EndCondition.Count, {endCondition = RRule.EndCondition.Count(1)}, shape = SegmentedButtonDefaults.itemShape(1, 3)) {
                        Text("Count")
                    }
                    SegmentedButton(endCondition is RRule.EndCondition.Until, {endCondition = RRule.EndCondition.Until(startDate)}, shape = SegmentedButtonDefaults.itemShape(2, 3)) {
                        Text("Until")
                    }
                }
                if (endCondition is RRule.EndCondition.Count) {
                    var countStr by remember { mutableStateOf((endCondition as RRule.EndCondition.Count).count.toString()) }
                    OutlinedTextField(countStr, { new ->
                        val v = new.toLongOrNull() ?: 1L
                        countStr = new
                        endCondition = RRule.EndCondition.Count(v)
                    }, label = { Text("Count") })
                }
                if(endCondition is RRule.EndCondition.Until) {
                    OutlinedTextField(
                        "Until: ${(endCondition as RRule.EndCondition.Until).date.format(dateFormat)}",
                        { },
                        readOnly = true,
                        interactionSource = remember { MutableInteractionSource() }
                            .also { interactionSource ->
                                LaunchedEffect(interactionSource) {
                                    interactionSource.interactions.collect {
                                        if (it is PressInteraction.Release) {
                                            val current = endCondition as RRule.EndCondition.Until
                                            backStack.add(Route.EditEvent.DatePickerDialog(KEY_UNTIL, current.date, startDate))
                                        }
                                    }
                                }
                            }
                    )
                }
            }
        }
    )
}

private fun ordinal(int: Int): String {
    return int.toString() + (when (int % 100) {
        1 -> "st"
        2 -> "nd"
        3 -> "rd"
        in 4..20 -> "th"
        else -> null
    } ?: when (int % 10) {
        1 -> "st"
        2 -> "nd"
        3 -> "rd"
        else -> "th"
    })
}

fun String.capitalcase(): String {
    return take(1).uppercase() + drop(1).lowercase()
}

package com.vayunmathur.calendar.ui.dialogs
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
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.SegmentedButton
import com.vayunmathur.library.ui.SegmentedButtonDefaults
import com.vayunmathur.library.ui.SingleChoiceSegmentedButtonRow
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.calendar.util.RRule
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.util.RecurrenceParams
import com.vayunmathur.calendar.Route
import com.vayunmathur.calendar.ui.dateFormat
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.ResultEffect
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.format
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number

private const val KEY_UNTIL = "RecurranceDialog.until"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurrenceDialog(backStack: NavBackStack<Route>, resultKey: String, startDate: LocalDate, initial: RecurrenceParams?) {
    val registry = LocalNavResultRegistry.current
    val scope = rememberCoroutineScope()

    var freq by remember { mutableStateOf(initial?.freq ?: "days") }
    var intervalStr by remember { mutableStateOf((initial?.interval ?: 1).toString()) }
    var daysOfWeek by remember {
        mutableStateOf(initial?.daysOfWeek?.ifEmpty { listOf(startDate.dayOfWeek) } ?: listOf(startDate.dayOfWeek))
    }
    var endCondition by remember { mutableStateOf(initial?.endCondition ?: RRule.EndCondition.Never) }

    // Which "On..." preset is chosen for monthly / yearly. Every preset is derived
    // entirely from the start date, so we only need to remember which one is selected.
    var monthlyOption by remember { mutableIntStateOf(initial?.monthlyType ?: 0) }
    var yearlyOption by remember {
        mutableIntStateOf(
            when {
                !initial?.byWeekNo.isNullOrEmpty() -> 1
                !initial?.byYearDay.isNullOrEmpty() -> 2
                else -> 0
            }
        )
    }

    // listen for date picker result (used for the UNTIL end condition)
    ResultEffect<LocalDate>(KEY_UNTIL) { selected ->
        endCondition = RRule.EndCondition.Until(selected)
    }

    // Values derived from the chosen start date, used to label the preset options.
    val weekdayFull = startDate.dayOfWeek.name.lowercase().replaceFirstChar { it.titlecase() }
    val nthOfMonth = ordinal((startDate.day - 1) / 7 + 1)
    val monthName = MonthNames.ENGLISH_FULL.names[startDate.month.number - 1]

    AlertDialog(
        onDismissRequest = { backStack.pop() },
        confirmButton = {
            Button(onClick = {
                val interval = intervalStr.toIntOrNull() ?: 1
                val rrule: RRule = when (freq) {
                    "days" -> RRule.EveryXDays(interval, endCondition)
                    "weeks" -> RRule.EveryXWeeks(
                        interval,
                        daysOfWeek.ifEmpty { listOf(startDate.dayOfWeek) },
                        endCondition
                    )
                    "months" -> when (monthlyOption) {
                        1 -> RRule.EveryXMonths(interval, 1, endCondition)
                        2 -> RRule.EveryXMonths(interval, 2, endCondition)
                        else -> RRule.EveryXMonths(interval, 0, endCondition, byMonthDay = listOf(startDate.day))
                    }
                    else -> when (yearlyOption) {
                        1 -> RRule.EveryXYears(
                            interval, endCondition,
                            byWeekNo = listOf(isoWeekNumber(startDate)),
                            byDay = listOf(startDate.dayOfWeek)
                        )
                        2 -> RRule.EveryXYears(
                            interval, endCondition,
                            byYearDay = listOf(dayOfYear(startDate))
                        )
                        else -> RRule.EveryXYears(
                            interval, endCondition,
                            byMonth = listOf(startDate.month.number),
                            byMonthDay = listOf(startDate.day)
                        )
                    }
                }

                scope.launch { registry.dispatchResult(resultKey, rrule) }
                backStack.pop()
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            Button(onClick = { backStack.pop() }) { Text(stringResource(R.string.cancel)) }
        },
        text = {
            Column(Modifier.padding(8.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.repeat))
                    var openDropdown by remember { mutableStateOf(false) }

                    OutlinedTextField(
                        intervalStr,
                        { intervalStr = it },
                        leadingIcon = {Text(stringResource(R.string.every))},
                        trailingIcon = {
                            Text(stringResource(R.string.dropdown_freq_format, freq), Modifier.clickable{
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
                    Text(stringResource(R.string.on_days_of_week))
                    val dayOfWeekCircle = @Composable { d: DayOfWeek ->
                        Surface(Modifier.clickable {
                            daysOfWeek = if (daysOfWeek.contains(d)) daysOfWeek - d else daysOfWeek + d
                        }, color = if(d in daysOfWeek) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                            shape = CircleShape) {
                            Box(Modifier.size(50.dp), contentAlignment = Alignment.Center) {
                                Text(d.name.take(3).lowercase().replaceFirstChar { it.titlecase() })
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
                    OnDropdown(
                        label = stringResource(R.string.on_label),
                        options = listOf(
                            stringResource(R.string.month_option_day_of_month, startDate.day),
                            stringResource(R.string.month_option_nth_weekday, nthOfMonth, weekdayFull),
                            stringResource(R.string.month_option_last_weekday, weekdayFull)
                        ),
                        selected = monthlyOption,
                        onSelect = { monthlyOption = it }
                    )
                }

                if (freq == "years") {
                    OnDropdown(
                        label = stringResource(R.string.on_label),
                        options = listOf(
                            stringResource(R.string.year_option_month_day, monthName, startDate.day),
                            stringResource(R.string.year_option_week, isoWeekNumber(startDate), weekdayFull),
                            stringResource(R.string.year_option_day_of_year, dayOfYear(startDate))
                        ),
                        selected = yearlyOption,
                        onSelect = { yearlyOption = it }
                    )
                }

                Text(stringResource(R.string.end))

                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(endCondition is RRule.EndCondition.Never, {endCondition = RRule.EndCondition.Never}, shape = SegmentedButtonDefaults.itemShape(0, 3)) {
                        Text(stringResource(R.string.never))
                    }
                    SegmentedButton(endCondition is RRule.EndCondition.Count, {endCondition = RRule.EndCondition.Count(1)}, shape = SegmentedButtonDefaults.itemShape(1, 3)) {
                        Text(stringResource(R.string.count))
                    }
                    SegmentedButton(endCondition is RRule.EndCondition.Until, {endCondition = RRule.EndCondition.Until(startDate)}, shape = SegmentedButtonDefaults.itemShape(2, 3)) {
                        Text(stringResource(R.string.until))
                    }
                }
                if (endCondition is RRule.EndCondition.Count) {
                    var countStr by remember { mutableStateOf((endCondition as RRule.EndCondition.Count).count.toString()) }
                    OutlinedTextField(countStr, { new ->
                        val v = new.toLongOrNull() ?: 1L
                        countStr = new
                        endCondition = RRule.EndCondition.Count(v)
                    }, label = { Text(stringResource(R.string.count)) })
                }
                if(endCondition is RRule.EndCondition.Until) {
                    OutlinedTextField(
                        stringResource(R.string.until_date, (endCondition as RRule.EndCondition.Until).date.format(dateFormat)),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnDropdown(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = options.getOrElse(selected) { options.firstOrNull() ?: "" },
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            trailingIcon = { Text("▼") },
            interactionSource = remember { MutableInteractionSource() }.also { src ->
                LaunchedEffect(src) {
                    src.interactions.collect { if (it is PressInteraction.Release) open = true }
                }
            }
        )
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, opt ->
                DropdownMenuItem({ Text(opt) }, onClick = {
                    onSelect(i)
                    open = false
                })
            }
        }
    }
}

private fun dayOfYear(date: LocalDate): Int =
    (date.toEpochDays() - LocalDate(date.year, 1, 1).toEpochDays() + 1).toInt()

private fun isoWeeksInYear(year: Int): Int {
    val jan1 = LocalDate(year, 1, 1).dayOfWeek.isoDayNumber
    val leap = (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
    return if (jan1 == 4 || (leap && jan1 == 3)) 53 else 52
}

private fun isoWeekNumber(date: LocalDate): Int {
    val week = (dayOfYear(date) - date.dayOfWeek.isoDayNumber + 10) / 7
    return when {
        week < 1 -> isoWeeksInYear(date.year - 1)
        week > isoWeeksInYear(date.year) -> 1
        else -> week
    }
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

package com.vayunmathur.calendar.ui.dialogs
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.DateNameStyle
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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.calendar.domain.MAX_RRULE_COUNT
import com.vayunmathur.calendar.domain.RRule
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.domain.RecurrenceDates
import com.vayunmathur.calendar.domain.RecurrenceParams
import com.vayunmathur.calendar.Route
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.ResultEffect
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import com.vayunmathur.library.util.localizedMonthNames
import com.vayunmathur.library.util.localizedDayOfWeekNames
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number

private const val KEY_UNTIL = "RecurranceDialog.until"
private const val KEY_ADD_DATE = "RecurranceDialog.addDate"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurrenceDialog(
    backStack: NavBackStack<Route>,
    resultKey: String,
    startDate: LocalDate,
    initial: RecurrenceParams?,
    initialDates: List<LocalDate> = emptyList(),
) {
    val registry = LocalNavResultRegistry.current
    val scope = rememberCoroutineScope()

    // Two ways to repeat: a pattern (RRULE) or a hand-picked set of dates (RDATE). They are
    // mutually exclusive, so the dialog is a mode switch over one confirm button.
    var onDates by remember { mutableStateOf(initialDates.isNotEmpty()) }
    var dates by remember { mutableStateOf(initialDates.filter { it != startDate }.distinct().sorted()) }

    ResultEffect<LocalDate>(KEY_ADD_DATE) { selected ->
        if (selected != startDate) dates = (dates + selected).distinct().sorted()
    }

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
    val weekdayFull = localizedDayOfWeekNames(DateNameStyle.FULL)[startDate.dayOfWeek.isoDayNumber - 1]
    val nthOfMonth = ordinal((startDate.day - 1) / 7 + 1)
    val monthName = localizedMonthNames(DateNameStyle.FULL)[startDate.month.number - 1]

    AlertDialog(
        onDismissRequest = { backStack.pop() },
        confirmButton = {
            Button(onClick = {
                if (onDates) {
                    scope.launch { registry.dispatchResult(resultKey, RecurrenceDates(dates)) }
                    backStack.pop()
                    return@Button
                }
                // Clamp before buildRRuleString(): 0/negative/unparseable fall back to 1 so the
                // provider never sees INTERVAL=0 (rejected/misbehaving).
                val interval = (intervalStr.toIntOrNull() ?: 1).coerceAtLeast(1)
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
            }) { Text(stringResource(UiR.string.ok)) }
        },
        dismissButton = {
            Button(onClick = { backStack.pop() }) { Text(stringResource(UiR.string.cancel)) }
        },
        text = {
            Column(Modifier.padding(8.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {

                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(!onDates, { onDates = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                        Text(stringResource(R.string.repeat_mode_pattern))
                    }
                    SegmentedButton(onDates, { onDates = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                        Text(stringResource(R.string.repeat_mode_dates))
                    }
                }

                if (onDates) {
                    ChosenDates(
                        startDate = startDate,
                        dates = dates,
                        onRemove = { dates = dates - it },
                        onAdd = {
                            backStack.add(
                                Route.EditEvent.DatePickerDialog(
                                    KEY_ADD_DATE,
                                    dates.lastOrNull() ?: startDate,
                                    startDate,
                                )
                            )
                        },
                    )
                    return@Column
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.repeat))
                    var openDropdown by remember { mutableStateOf(false) }

                    Box(Modifier.weight(1f)) {
                        OutlinedTextField(
                            intervalStr,
                            { intervalStr = it.filter { c -> c.isDigit() } },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            prefix = {
                                Text(
                                    stringResource(R.string.every),
                                    Modifier.padding(end = 8.dp)
                                )
                            },
                            suffix = {
                                Text(
                                    stringResource(R.string.dropdown_freq_format, freq),
                                    Modifier.padding(start = 8.dp).clickable {
                                        openDropdown = true
                                    }
                                )
                            }
                        )
                        DropdownMenu(openDropdown, onDismissRequest = { openDropdown = false }) {
                            listOf("days", "weeks", "months", "years").forEach { f ->
                                DropdownMenuItem({ Text(f) }, onClick = {
                                    freq = f
                                    openDropdown = false
                                })
                            }
                        }
                    }
                }

                if (freq == "weeks") {
                    Text(stringResource(R.string.on_days_of_week))
                    val dayOfWeekCircle = @Composable { d: DayOfWeek ->
                        Surface(Modifier.clickable {
                            daysOfWeek = if (daysOfWeek.contains(d)) daysOfWeek - d else daysOfWeek + d
                        }, color = if(d in daysOfWeek) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                            shape = CircleShape) {
                            Box(Modifier.size(50.dp), contentAlignment = Alignment.Center) {
                                Text(localizedDayOfWeekNames(DateNameStyle.SHORT)[d.isoDayNumber - 1])
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
                        // Clamp here too: reject 0/negative/unparseable with a 1 fallback, cap huge
                        // values at MAX_RRULE_COUNT, and write the clamped value back so the field
                        // shows what the rule will actually carry.
                        val v = (new.toLongOrNull() ?: 1L).coerceIn(1L, MAX_RRULE_COUNT)
                        countStr = v.toString()
                        endCondition = RRule.EndCondition.Count(v)
                    }, label = { Text(stringResource(R.string.count)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                if(endCondition is RRule.EndCondition.Until) {
                    OutlinedTextField(
                        stringResource(R.string.until_date, DateString.dateWeekday((endCondition as RRule.EndCondition.Until).date)),
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

package com.vayunmathur.todo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ModalBottomSheet
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.todo.R
import com.vayunmathur.todo.domain.Recurrence
import java.time.format.TextStyle
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.isoDayNumber

private enum class Unit4 { DAYS, WEEKS, MONTHS, YEARS }

/** Preset repeats plus a custom editor (interval, unit, weekdays, last day, from completion). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecurrenceSheet(current: Recurrence?, onSet: (Recurrence?) -> Unit, onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (!custom) {
            val presets = listOf(
                null,
                Recurrence.Daily(),
                Recurrence.Weekly(1, DayOfWeek.entries.take(WORKDAYS).toSet()),
                Recurrence.Weekly(),
                Recurrence.Monthly(),
                Recurrence.Yearly(),
            )
            presets.forEach { preset ->
                ListItem(
                    headlineContent = {
                        Text(
                            when {
                                preset == null -> stringResource(R.string.no_repeat)
                                preset == presets[2] -> stringResource(R.string.weekdays)
                                else -> recurrenceLabel(preset)
                            },
                        )
                    },
                    trailingContent = if (preset == current) ({ IconCheck() }) else null,
                    modifier = Modifier.clickable { onSet(preset) },
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.custom)) },
                supportingContent = current?.takeIf { it !in presets }?.let { { Text(recurrenceLabel(it)) } },
                modifier = Modifier.clickable { custom = true },
            )
        } else {
            CustomRecurrence(current, onSet)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomRecurrence(current: Recurrence?, onSet: (Recurrence?) -> Unit) {
    var interval by remember { mutableStateOf((current?.interval ?: 1).toString()) }
    var unit by remember {
        mutableStateOf(
            when (current) {
                is Recurrence.Weekly -> Unit4.WEEKS
                is Recurrence.Monthly -> Unit4.MONTHS
                is Recurrence.Yearly -> Unit4.YEARS
                else -> Unit4.DAYS
            },
        )
    }
    var days by remember { mutableStateOf((current as? Recurrence.Weekly)?.days.orEmpty()) }
    var lastDay by remember { mutableStateOf((current as? Recurrence.Monthly)?.dayOfMonth == Recurrence.LAST_DAY) }
    var fromCompletion by remember { mutableStateOf(current is Recurrence.AfterCompletion) }
    val n = interval.toIntOrNull()?.coerceIn(1, MAX_INTERVAL) ?: 1

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.every))
            OutlinedTextField(
                value = interval,
                onValueChange = { interval = it.filter(Char::isDigit).take(3) },
                modifier = Modifier.width(80.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Unit4.entries.forEach { u ->
                FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(stringResource(unitLabel(u))) })
            }
        }
        if (unit == Unit4.WEEKS && !fromCompletion) {
            val locale = LocalConfiguration.current.locales[0]
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DayOfWeek.entries.forEach { d ->
                    FilterChip(
                        selected = d in days,
                        onClick = { days = if (d in days) days - d else days + d },
                        label = { Text(java.time.DayOfWeek.of(d.isoDayNumber).getDisplayName(TextStyle.NARROW, locale)) },
                    )
                }
            }
        }
        if (unit == Unit4.MONTHS) SwitchRow(stringResource(R.string.last_day_of_month), lastDay) { lastDay = it }
        if (unit == Unit4.DAYS || unit == Unit4.WEEKS) {
            SwitchRow(stringResource(R.string.from_completion), fromCompletion) { fromCompletion = it }
        }
        Button(onClick = {
            onSet(
                when {
                    fromCompletion && unit == Unit4.DAYS -> Recurrence.AfterCompletion(n)
                    fromCompletion && unit == Unit4.WEEKS -> Recurrence.AfterCompletion(n * DAYS_PER_WEEK)
                    unit == Unit4.DAYS -> Recurrence.Daily(n)
                    unit == Unit4.WEEKS -> Recurrence.Weekly(n, days)
                    unit == Unit4.MONTHS -> Recurrence.Monthly(n, if (lastDay) Recurrence.LAST_DAY else null)
                    else -> Recurrence.Yearly(n)
                },
            )
        }) { Text(stringResource(R.string.done_action)) }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun unitLabel(u: Unit4) = when (u) {
    Unit4.DAYS -> R.string.unit_days
    Unit4.WEEKS -> R.string.unit_weeks
    Unit4.MONTHS -> R.string.unit_months
    Unit4.YEARS -> R.string.unit_years
}

private const val WORKDAYS = 5
private const val DAYS_PER_WEEK = 7
private const val MAX_INTERVAL = 999

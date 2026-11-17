package com.vayunmathur.calculator.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.calculator.R
import com.vayunmathur.calculator.platform.CalculatorActions
import com.vayunmathur.calculator.platform.CalculatorUiState
import com.vayunmathur.calculator.domain.UnitCategory
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AssistChip
import com.vayunmathur.library.ui.ModalBottomSheet
import com.vayunmathur.library.ui.NavigationDrawerItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.VerticalDivider
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatePickerModal(onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    val dateState = rememberDatePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { dateState.selectedDateMillis?.let(onConfirm) ?: onDismiss() }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(UiR.string.cancel)) } },
    ) {
        DatePicker(state = dateState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimePickerModal(onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit) {
    val timeState = rememberTimePickerState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_time)) },
        text = { TimePicker(state = timeState) },
        confirmButton = {
            TextButton(onClick = { onConfirm(timeState.hour, timeState.minute) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(UiR.string.cancel)) } },
    )
}

/** The system date picker returns UTC midnight of the chosen day; reinterpret that calendar day
 * at local midnight so the stored instant reads back as that date. */
internal fun localMidnightSeconds(utcDateMillis: Long): Long =
    Instant.ofEpochMilli(utcDateMillis).atZone(ZoneOffset.UTC).toLocalDate()
        .atStartOfDay(ZoneId.systemDefault()).toEpochSecond()

/** Combine a picked calendar day (UTC midnight millis) with a local time-of-day into an instant. */
internal fun combineDateTimeSeconds(utcDateMillis: Long, hour: Int, minute: Int): Long =
    Instant.ofEpochMilli(utcDateMillis).atZone(ZoneOffset.UTC).toLocalDate()
        .atTime(hour, minute).atZone(ZoneId.systemDefault()).toEpochSecond()

/**
 * A time-of-day picked with no date: today at that local time. Kept absolute (an instant)
 * rather than a duration so 2 AM never casts to 2 hours — `5 AM + 2 AM` errors, matching the
 * engine's rule that two instants only subtract.
 */
internal fun todayAtTimeSeconds(hour: Int, minute: Int): Long =
    LocalDate.now(ZoneId.systemDefault()).atTime(hour, minute)
        .atZone(ZoneId.systemDefault()).toEpochSecond()

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UnitPickerSheet(
    state: CalculatorUiState,
    actions: CalculatorActions,
    onDismiss: () -> Unit,
) {
    // The insert sheet mirrors the registry's equation-usable categories, plus a synthetic
    // "Absolute time" tab right after Time that holds the date/time pickers. It is picker-only
    // (empty units mark it as such below), so it lives here rather than in UnitRegistry — the
    // parser, output selector and widget never see it.
    val absoluteTimeName = stringResource(R.string.absolute_time)
    val categories = remember(state.unitCategories, absoluteTimeName) {
        val list = state.unitCategories.filter { it.inEquations }.toMutableList()
        val timeIndex = list.indexOfFirst { it.name == "Time" }
        list.add(
            if (timeIndex >= 0) timeIndex + 1 else list.size,
            UnitCategory(absoluteTimeName, emptyList(), inEquations = false),
        )
        list
    }
    var categoryIndex by remember { mutableIntStateOf(0) }
    // Which absolute-time picker (if any) is open. DtDate/DtTime are the two steps of the
    // combined date-and-time flow.
    var absPicker by remember { mutableStateOf(AbsoluteTimePicker.None) }
    // The date chosen in the first step of the combined date-and-time flow (UTC midnight millis).
    var dtDateMillis by remember { mutableStateOf<Long?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.insert_unit),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            fontSize = 20.sp,
        )
        if (categories.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 480.dp)) {
                // The category list owns the vertical space: a full-height column of rows
                // rather than a horizontal tab strip, so no category hides off the edge.
                LazyColumn(
                    Modifier.weight(0.45f).padding(vertical = 8.dp),
                ) {
                    items(categories.size) { index ->
                        NavigationDrawerItem(
                            label = { Text(categories[index].name, maxLines = 1) },
                            selected = index == categoryIndex,
                            onClick = { categoryIndex = index },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                VerticalDivider()
                Box(Modifier.weight(0.55f).fillMaxHeight()) {
                    categories.getOrNull(categoryIndex)?.let { category ->
                        if (category.units.isEmpty()) {
                            AbsoluteTimePickers(
                                onPick = { absPicker = it },
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            )
                        } else {
                            FlowRow(
                                Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(16.dp),
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                            ) {
                                category.units.forEach { unit ->
                                    AssistChip(
                                        onClick = { actions.append(unit.token); onDismiss() },
                                        label = { Text(unit.symbol) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    when (absPicker) {
        AbsoluteTimePicker.Date -> DatePickerModal(onDismiss = { absPicker = AbsoluteTimePicker.None }) { millis ->
            actions.insertInstant(localMidnightSeconds(millis))
            onDismiss()
        }
        AbsoluteTimePicker.DtDate -> DatePickerModal(onDismiss = { absPicker = AbsoluteTimePicker.None }) { millis ->
            dtDateMillis = millis
            absPicker = AbsoluteTimePicker.DtTime
        }
        // Time-of-day with no date: today at that time, kept absolute so 2 AM never casts
        // to 2 hours — `5 AM + 2 AM` errors like any other instant + instant.
        AbsoluteTimePicker.Time -> TimePickerModal(onDismiss = { absPicker = AbsoluteTimePicker.None }) { hour, minute ->
            actions.insertInstant(todayAtTimeSeconds(hour, minute))
            onDismiss()
        }
        AbsoluteTimePicker.DtTime -> TimePickerModal(onDismiss = { absPicker = AbsoluteTimePicker.None }) { hour, minute ->
            dtDateMillis?.let { actions.insertInstant(combineDateTimeSeconds(it, hour, minute)) }
            onDismiss()
        }
        AbsoluteTimePicker.None -> {}
    }
}

/** Which absolute-time picker (if any) is currently open. `DtDate`/`DtTime` are the two steps
 * of the combined date-and-time flow. */
internal enum class AbsoluteTimePicker { None, Date, Time, DtDate, DtTime }

/**
 * The Absolute-time tab's content: date, time-of-day and combined pickers that insert
 * `#<epoch>` instants into the keypad input.
 */
@Composable
private fun AbsoluteTimePickers(onPick: (AbsoluteTimePicker) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(
        modifier,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(onClick = { onPick(AbsoluteTimePicker.Date) }, label = { Text(stringResource(R.string.key_date)) })
        AssistChip(onClick = { onPick(AbsoluteTimePicker.Time) }, label = { Text(stringResource(R.string.key_time)) })
        AssistChip(onClick = { onPick(AbsoluteTimePicker.DtDate) }, label = { Text(stringResource(R.string.key_datetime)) })
    }
}

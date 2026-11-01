package com.vayunmathur.travel.ui

import com.vayunmathur.library.ui.R as UiR
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.DateRangePicker
import com.vayunmathur.library.ui.DatePickerDialog
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.SelectableDates
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.rememberDateRangePickerState
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

/**
 * Two side-by-side date fields (e.g. Depart / Return, Check-in / Check-out)
 * that open one range calendar: tap either to pick both dates at once. Values
 * are ISO `YYYY-MM-DD` ("" when unset); dates before [minDate] are disabled.
 * [allowSameDay] = false requires the end to be after the start (hotel nights).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangeFields(
    startLabel: String,
    endLabel: String,
    start: String,
    end: String,
    onRange: (start: String, end: String) -> Unit,
    modifier: Modifier = Modifier,
    minDate: LocalDate? = null,
    allowSameDay: Boolean = true,
) {
    var show by remember { mutableStateOf(false) }
    val display = { iso: String ->
        runCatching { DateString.dateWeekdayNoYear(LocalDate.parse(iso)) }.getOrDefault(iso)
    }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DateFieldBox(startLabel, display(start), onClick = { show = true }, modifier = Modifier.weight(1f))
        DateFieldBox(endLabel, display(end), onClick = { show = true }, modifier = Modifier.weight(1f))
    }
    if (!show) return

    val minMillis = minDate?.atStartOfDayIn(TimeZone.UTC)?.toEpochMilliseconds()
    val selectable = remember(minMillis) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                minMillis == null || utcTimeMillis >= minMillis
        }
    }
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = isoToUtcMillis(start),
        initialSelectedEndDateMillis = isoToUtcMillis(end),
        selectableDates = selectable,
    )
    val startMs = state.selectedStartDateMillis
    val endMs = state.selectedEndDateMillis
    val complete = startMs != null && endMs != null && (allowSameDay || endMs > startMs)
    DatePickerDialog(
        onDismissRequest = { show = false },
        confirmButton = {
            TextButton(
                onClick = {
                    if (startMs != null && endMs != null) onRange(utcMillisToIso(startMs), utcMillisToIso(endMs))
                    show = false
                },
                enabled = complete,
            ) { Text(stringResource(UiR.string.ok)) }
        },
        dismissButton = { TextButton(onClick = { show = false }) { Text(stringResource(UiR.string.cancel)) } },
    ) {
        DateRangePicker(state, modifier = Modifier.weight(1f))
    }
}

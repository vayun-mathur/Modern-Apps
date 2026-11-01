package com.vayunmathur.travel.ui

import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.travel.R
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
import com.vayunmathur.library.ui.DatePickerDialog
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import kotlinx.datetime.LocalDate

/**
 * Two side-by-side date fields (e.g. Depart / Return, Check-in / Check-out)
 * that open one range calendar ([RangeCalendar], styled like the single date
 * picker) in the same dialog: tap either to pick both dates at once. Values
 * are ISO `YYYY-MM-DD` ("" when unset); dates before [minDate] are disabled.
 * [allowSameDay] = false requires the end to be after the start (hotel nights).
 */
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

    // Work on a draft so Cancel leaves the fields untouched.
    var draftStart by remember { mutableStateOf(start.toJavaDate()) }
    var draftEnd by remember { mutableStateOf(end.toJavaDate()) }
    val complete = draftStart != null && draftEnd != null
    DatePickerDialog(
        onDismissRequest = { show = false },
        confirmButton = {
            TextButton(
                onClick = {
                    val s = draftStart
                    val e = draftEnd
                    if (s != null && e != null) onRange(s.toString(), e.toString())
                    show = false
                },
                enabled = complete,
            ) { Text(stringResource(UiR.string.ok)) }
        },
        dismissButton = { TextButton(onClick = { show = false }) { Text(stringResource(UiR.string.cancel)) } },
    ) {
        RangeCalendar(
            title = stringResource(R.string.select_dates),
            startLabel = startLabel,
            endLabel = endLabel,
            start = draftStart,
            end = draftEnd,
            onChange = { s, e ->
                draftStart = s
                draftEnd = e
            },
            minDate = minDate?.let { java.time.LocalDate.parse(it.toString()) } ?: java.time.LocalDate.now(),
            allowSameDay = allowSameDay,
        )
    }
}

private fun String.toJavaDate(): java.time.LocalDate? = runCatching { java.time.LocalDate.parse(this) }.getOrNull()

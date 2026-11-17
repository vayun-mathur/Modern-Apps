package com.vayunmathur.health.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberIs24Hour
import kotlinx.datetime.LocalTime
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.vayunmathur.health.data.Record

@Composable
fun SleepSummaryHeader(record: Record) {
    val context = LocalContext.current
    val totalMinutes = (record.value * 60).toLong()
    val startT = record.startTime.atZone(java.time.ZoneId.systemDefault())
    val endT = record.endTime.atZone(java.time.ZoneId.systemDefault())
    val is24 = rememberIs24Hour()

    Column {
        Text(
            text = hoursMinutesString(context, totalMinutes),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Light,
            color = HealthColors.Sleep,
        )
        Text(
            text = "${DateString.time(LocalTime(startT.hour, startT.minute), is24)} - ${DateString.time(LocalTime(endT.hour, endT.minute), is24)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

package com.vayunmathur.calendar.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.platform.describe
import com.vayunmathur.calendar.data.Event
import java.io.InputStream

@Composable
fun EventCard(event: Event) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        ListItem({
            Text(event.title)
        }, supportingContent = {
            Column {
                // Format date range using the shared helper
                Text(dateRangeString(context, event.startDateTimeDisplay.date, event.endDateTimeDisplay.date, event.startDateTimeDisplay.time, event.endDateTimeDisplay.time, event.allDay))
                // RRULE text
                event.rrule?.let { Text(it.describe(context)) }
                if (event.rdate.isNotEmpty()) {
                    Text(
                        pluralStringResource(
                            R.plurals.repeat_dates_summary,
                            event.rdate.size + 1,
                            event.rdate.size + 1,
                        )
                    )
                }

                if (event.description.isNotBlank()) {
                    Text(event.description)
                }
                if (event.location.isNotBlank()) {
                    Text(event.location)
                }
            }
        }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
    }
}

// Thin UI-package entry point; implementation lives in IcsParser.kt.
fun parseICSFile(iS: InputStream): List<Event> = parseICSFileImpl(iS)

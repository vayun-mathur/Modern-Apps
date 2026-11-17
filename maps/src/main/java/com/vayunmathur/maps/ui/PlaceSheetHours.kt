package com.vayunmathur.maps.ui
import com.vayunmathur.maps.data.string

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.util.DateNameStyle
import com.vayunmathur.library.ui.IconSchedule
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.verticalShape
import com.vayunmathur.library.util.firstLetterUppercase
import com.vayunmathur.library.util.localizedDayOfWeekNames
import com.vayunmathur.maps.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.maps.data.OpeningHours
import com.vayunmathur.maps.data.timeFormat
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

@Composable
internal fun OsmHours(openingHours: OpeningHours, todayOverride: Pair<DayOfWeek, String>? = null) {
    var showDetails by remember { mutableStateOf(false) }

    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val isOpen = openingHours.isOpen(now)
    val nextChangeTime = openingHours.nextStatusChangeTime(now)
    val openStr = stringResource(UiR.string.open)
    val closedStr = stringResource(R.string.closed_status)
    val closesAtStr = stringResource(R.string.closes_at, nextChangeTime.time.format(timeFormat))
    val opensAtStr = stringResource(R.string.opens_at, nextChangeTime.time.format(timeFormat))
    val openColor = MaterialTheme.colorScheme.tertiary
    val closedColor = MaterialTheme.colorScheme.error
    val liveLabel = stringResource(R.string.poi_hours_live)
    val text = AnnotatedString.Builder().apply {
        if (isOpen) withStyle(SpanStyle(openColor)) { append(openStr) } else withStyle(SpanStyle(closedColor)) { append(closedStr) }
        append(" \u2022 ")
        if (isOpen) append(closesAtStr) else append(opensAtStr)
        if (nextChangeTime.date != now.date) append(" ${localizedDayOfWeekNames(DateNameStyle.FULL)[nextChangeTime.date.dayOfWeek.isoDayNumber - 1]}")
    }.toAnnotatedString()
    Column {
        RestaurantItem(
            { IconSchedule() },
            text,
            shape = verticalShape(0, if (showDetails) 2 else 1),
        ) {
            showDetails = !showDetails
        }
        if (showDetails) {
            Spacer(Modifier.padding(2.dp))
            Card(shape = verticalShape(1, 2)) {
                for ((day, hours) in openingHours.openingHours()) {
                    // Today's row shows Google's live value when it sent one, tagged so
                    // the swap is visible; the other six stay straight off OSM.
                    val live = todayOverride?.takeIf { it.first == day }?.second
                    val trailing = if (live != null) {
                        AnnotatedString.Builder().apply {
                            append(live)
                            append("  ")
                            withStyle(SpanStyle(openColor)) { append(liveLabel) }
                        }.toAnnotatedString()
                    } else AnnotatedString(hours)
                    ListItem(
                        { Text(day.name.lowercase().firstLetterUppercase()) },
                        leadingContent = {},
                        trailingContent = { Text(trailing) },
                        colors = ListItemDefaults.colors(Color.Transparent),
                    )
                }
            }
        }
    }
}

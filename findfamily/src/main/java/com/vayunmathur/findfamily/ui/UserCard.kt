package com.vayunmathur.findfamily.ui

import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.is24Hour
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.findfamily.R
import com.vayunmathur.library.ui.R as UiR
import androidx.compose.runtime.Composable
import com.vayunmathur.findfamily.data.LocationSource
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.library.util.formatSpeed
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun UserCard(user: User, locationValue: LocationValue?, showSupportingContent: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val lastUpdatedTime = locationValue?.let { timestring(it.timestamp, false, context) } ?: stringResource(R.string.auto_toggle_never)
    val speedString = (locationValue?.speed ?: 0f).formatSpeed()
    val sinceTime = user.lastLocationChangeTime.toLocalDateTime(TimeZone.currentSystemDefault())
    val timeSinceEntry = Clock.System.now() - user.lastLocationChangeTime
    val sinceString = when {
        user.locationName == "Unnamed Location" -> ""
        timeSinceEntry < 60.seconds -> stringResource(R.string.since_just_now)
        timeSinceEntry < 15.minutes -> pluralStringResource(R.plurals.since_minutes_ago, timeSinceEntry.inWholeMinutes.toInt(), timeSinceEntry.inWholeMinutes)
        else -> {
            val formattedTime = DateString.time(sinceTime.time, is24Hour(context))
            val formattedDate = when (Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toEpochDays() - sinceTime.date.toEpochDays()) {
                0L -> stringResource(UiR.string.today)
                1L -> stringResource(R.string.yesterday)
                else -> DateString.monthDayYear(sinceTime.date)
            }
            stringResource(R.string.since_time_date, formattedTime, formattedDate)
        }
    }
    Card(if (showSupportingContent) Modifier.clickable(onClick = onClick) else Modifier) {
        ListItem(
            leadingContent = { UserPicture(user, 40.dp) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            content = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        user.name,
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            },
            supportingContent = {
                if (showSupportingContent) {
                    // A network sighting is not a fix: it is where some other phone happened to
                    // be standing when it heard this device. Saying "Updated 5 minutes ago at
                    // Home" would imply a precision and a freshness that isn't there, so it gets
                    // its own deliberately vaguer wording.
                    Text(
                        when (locationValue?.source) {
                            LocationSource.NETWORK_SIGHTING -> stringResource(
                                R.string.user_card_network_sighting,
                                lastUpdatedTime,
                                user.locationName
                            )
                            // Parting report: timestamp = when the fix was measured, reportedAt =
                            // the shutdown moment. Showing both keeps "last seen" truthful instead
                            // of claiming the phone was at the pin when it switched off. Clears
                            // on its own: the next LIVE heartbeat has a newer reportedAt and wins
                            // getLatest() again.
                            LocationSource.SHUTDOWN -> stringResource(
                                R.string.user_card_shutdown,
                                timestring(locationValue.reportedAt, false, context),
                                lastUpdatedTime,
                                user.locationName,
                                sinceString
                            )
                            LocationSource.BATTERY_LOW -> stringResource(
                                R.string.user_card_battery_low,
                                timestring(locationValue.reportedAt, false, context),
                                lastUpdatedTime,
                                user.locationName,
                                sinceString
                            )
                            else -> stringResource(
                                R.string.user_card_status,
                                lastUpdatedTime,
                                user.locationName,
                                sinceString
                            )
                        }
                    )
                }
            },
            trailingContent = {
                if (showSupportingContent) {
                    Column(horizontalAlignment = Alignment.End) {
                        // Speed and battery come from the device itself. A network sighting has
                        // neither — the numbers would be the finder's, so show nothing.
                        if (locationValue?.source != LocationSource.NETWORK_SIGHTING) {
                            Text(speedString, style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(2.dp))
                            locationValue?.battery?.let { BatteryBar(it) }
                        }
                    }
                }
            }
        )
    }
}



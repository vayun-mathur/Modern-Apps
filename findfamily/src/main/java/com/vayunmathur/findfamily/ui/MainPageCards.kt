package com.vayunmathur.findfamily.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.vayunmathur.library.ui.HistoryScrubberCard
import com.vayunmathur.library.ui.HistoryStep
import com.vayunmathur.library.ui.rememberHistoryScrubberState
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ExposedDropdownMenuDefaults
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.SelectableDropdownMenuItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.findfamily.ui.dialogs.interactionSourceClickable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.Route
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.data.toGeoPoint
import com.vayunmathur.findfamily.platform.FindFamilyViewModel
import com.vayunmathur.findfamily.platform.PersonActions
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.ResultEffect
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

internal val SheetPeekHeight = 200.dp

internal val SheetMinContentMargin = 48.dp

@Composable
fun BoxScope.HistoryScrubber(
    backStack: NavBackStack<Route>,
    ffViewModel: FindFamilyViewModel,
    userId: Long,
    setHistoricalPosition: (GeoPoint?) -> Unit
) {
    val state = rememberHistoryScrubberState(
        initialInstant = Clock.System.now(),
        initialNowMode = true,
        disallowFuture = true
    )

    val steps = listOf(
        HistoryStep(stringResource(R.string.history_step_minus_5m), -5 * 60L),
        HistoryStep(stringResource(R.string.history_step_minus_1m), -60L),
        HistoryStep(stringResource(R.string.history_step_minus_10s), -10L),
        HistoryStep(stringResource(R.string.history_step_plus_10s), 10L),
        HistoryStep(stringResource(R.string.history_step_plus_1m), 60L),
        HistoryStep(stringResource(R.string.history_step_plus_5m), 5 * 60L)
    )

    HistoryScrubberCard(
        state = state,
        steps = steps,
        onDateChipClick = { backStack.add(Route.UserPageHistoryDatePicker(state.date)) }
    )

    ResultEffect<LocalDate>("HistoryDatePicker") {
        state.setDate(it)
    }

    val locs by ffViewModel.locationHistory(userId).collectAsState(initial = emptyList())

    LaunchedEffect(state.instant, locs) {
        if (locs.isNotEmpty()) {
            val closest = locs.minBy { (it.timestamp - state.instant).absoluteValue }
            setHistoricalPosition(closest.coord.toGeoPoint())
        } else {
            // Nobody to show a pin for: drop any position left over from whoever was
            // viewed before, so their track never masquerades as this person's.
            setHistoricalPosition(null)
        }
    }
}

internal fun Modifier.fillMaxWidthFraction(percent: Float): Modifier =
    this.fillMaxWidth((percent / 100f).coerceIn(0f, 1f))

fun timestring(timestamp: Instant, future: Boolean, context: Context): String {
    val duration = (Clock.System.now() - timestamp).absoluteValue
    return when {
        duration.inWholeSeconds < 60 -> context.getString(if (future) R.string.time_very_soon else R.string.time_just_now)
        duration.inWholeMinutes < 60 -> context.resources.getQuantityString(if (future) R.plurals.time_in_minutes else R.plurals.time_minutes_ago, duration.inWholeMinutes.toInt(), duration.inWholeMinutes)
        duration.inWholeHours < 24 -> context.resources.getQuantityString(if (future) R.plurals.time_in_hours else R.plurals.time_hours_ago, duration.inWholeHours.toInt(), duration.inWholeHours)
        else -> context.resources.getQuantityString(if (future) R.plurals.time_in_days else R.plurals.time_days_ago, duration.inWholeDays.toInt(), duration.inWholeDays)
    }
}

internal fun formatAutoToggleCountdown(remaining: Duration): String {
    val totalSeconds = remaining.inWholeSeconds.coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}


package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.format
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

object HistoryDateFormats {
    val MONTH_DAY = LocalDate.Format {
        monthName(MonthNames.ENGLISH_ABBREVIATED)
        chars(" ")
        day()
    }

    val TIME_SECOND_AM_PM = LocalTime.Format {
        amPmHour()
        chars(":")
        minute()
        chars(":")
        second()
        chars(" ")
        amPmMarker("AM", "PM")
    }

    val DATE_INPUT = MONTH_DAY
}

/**
 * A single relative jump button for [HistoryScrubberCard]. [label] is shown on
 * the button; [deltaSeconds] is applied to the selected instant when tapped
 * (negative = into the past). Callers pick their own granularity — e.g. minutes
 * for FindFamily, hours/days for Astronomy.
 */
data class HistoryStep(val label: String, val deltaSeconds: Long)

/**
 * Holds the state for a [HistoryScrubberCard]. Owns the currently-selected
 * [instant] plus a [nowMode] flag. While in now mode the card ticks the instant
 * to the present every second; any user interaction (slider, step button, date
 * pick) leaves now mode and freezes time until [goNow] is called again.
 */
@OptIn(ExperimentalTime::class)
@Stable
class HistoryScrubberState internal constructor(
    val timeZone: TimeZone,
    initialInstant: Instant,
    initialNowMode: Boolean,
    private val disallowFuture: Boolean,
) {
    var nowMode by mutableStateOf(initialNowMode)
        private set
    var instant by mutableStateOf(initialInstant)
        private set

    val dateTime get() = instant.toLocalDateTime(timeZone)
    val date: LocalDate get() = dateTime.date
    val time: LocalTime get() = dateTime.time
    val secondOfDay: Int get() = time.toSecondOfDay()

    // Only user-driven changes respect the "no future" bound; live ticks are, by
    // definition, always the present. Recomputed each call so it never goes stale.
    private fun clampUser(candidate: Instant): Instant {
        if (disallowFuture) {
            val now = Clock.System.now()
            if (candidate > now) return now
        }
        return candidate
    }

    /** Slider drag: keep the date, change the time-of-day. Leaves now mode. */
    fun setSecondOfDay(sec: Int) {
        nowMode = false
        val clamped = sec.coerceIn(0, 86_399)
        instant = clampUser(date.atTime(LocalTime.fromSecondOfDay(clamped)).toInstant(timeZone))
    }

    /** Step button: shift the whole instant, possibly across a date boundary. Leaves now mode. */
    fun nudge(deltaSeconds: Long) {
        nowMode = false
        instant = clampUser(instant.plus(deltaSeconds.seconds))
    }

    /** Date-picker result: keep the time-of-day, change the date. Leaves now mode. */
    fun setDate(newDate: LocalDate) {
        nowMode = false
        instant = clampUser(newDate.atTime(time).toInstant(timeZone))
    }

    /** Now button: jump to the present and resume live tracking. */
    fun goNow() {
        instant = Clock.System.now()
        nowMode = true
    }

    internal fun tickNow() {
        if (nowMode) instant = Clock.System.now()
    }
}

@OptIn(ExperimentalTime::class)
@Composable
fun rememberHistoryScrubberState(
    initialInstant: Instant = Clock.System.now(),
    initialNowMode: Boolean = true,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
    disallowFuture: Boolean = false,
): HistoryScrubberState = remember {
    HistoryScrubberState(timeZone, initialInstant, initialNowMode, disallowFuture)
}

@Composable
fun RowScope.HistoryStepButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick,
        Modifier.weight(1f).heightIn(min = 36.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
    ) {
        Text(label, fontSize = 12.sp, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalTime::class)
@Composable
fun BoxScope.HistoryScrubberCard(
    state: HistoryScrubberState,
    steps: List<HistoryStep>,
    onDateChipClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Advance to the present once per second while in now mode. Leaving now mode
    // (via any user interaction) flips the flag and cancels this loop.
    LaunchedEffect(state.nowMode) {
        while (state.nowMode) {
            state.tickNow()
            delay(1000)
        }
    }

    Card(
        modifier.align(Alignment.BottomCenter)
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = state.secondOfDay.toFloat(),
                    onValueChange = { state.setSecondOfDay(it.roundToInt()) },
                    valueRange = 0f..86_399f,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    state.time.format(HistoryDateFormats.TIME_SECOND_AM_PM),
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    { onDateChipClick() },
                    { Text(state.date.format(HistoryDateFormats.DATE_INPUT)) }
                )
                Spacer(Modifier.weight(1f))
                FilterChip(
                    selected = state.nowMode,
                    onClick = { state.goNow() },
                    label = { Text("Now") }
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                steps.forEach { step ->
                    HistoryStepButton(step.label) { state.nudge(step.deltaSeconds) }
                }
            }
        }
    }
}

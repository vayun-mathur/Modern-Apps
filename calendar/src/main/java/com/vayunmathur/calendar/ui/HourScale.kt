package com.vayunmathur.calendar.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val FULL_HOUR_HEIGHT = 56.dp
private val MIN_FULL_HOUR_HEIGHT = 28.dp
private val COLLAPSED_HOUR_HEIGHT = 18.dp

/**
 * The vertical axis of the day/week grid: how tall each of the 24 hour rows is, and where any
 * minute of the day lands on it. Rows are not necessarily equal — the compact week view keeps
 * hours that contain events readable and squeezes the rest — so callers must map times through
 * [offsetOf] rather than multiplying by a single hour height.
 */
data class HourScale(val rowHeights: List<Dp>) {
    private val offsets: List<Dp> = buildList(25) {
        var acc = 0.dp
        add(acc)
        for (height in rowHeights) {
            acc += height
            add(acc)
        }
    }

    fun heightOf(hour: Int): Dp = rowHeights[hour]

    /** Where [minuteOfDay] (0..1440) sits, measured from the top of the grid. */
    fun offsetOf(minuteOfDay: Int): Dp {
        val minute = minuteOfDay.coerceIn(0, 24 * 60)
        val hour = minute / 60
        if (hour == 24) return offsets[24]
        return offsets[hour] + rowHeights[hour] * ((minute % 60) / 60f)
    }

    /** Factories for hour scales: uniform rows, or rows that collapse hours with no events. */
    companion object {
        fun uniform(): HourScale = HourScale(List(24) { FULL_HOUR_HEIGHT })

        /**
         * Hours in [busyHours] stay as tall as [available] allows; the rest shrink to a thin
         * strip, so a whole day fits on screen unless it is packed edge to edge.
         */
        fun collapsingEmptyHours(busyHours: Set<Int>, available: Dp): HourScale {
            val slack = available - COLLAPSED_HOUR_HEIGHT * (24 - busyHours.size)
            val fullHeight = if (busyHours.isEmpty()) FULL_HOUR_HEIGHT
            else (slack / busyHours.size).coerceIn(MIN_FULL_HOUR_HEIGHT, FULL_HOUR_HEIGHT)
            return HourScale(List(24) { if (it in busyHours) fullHeight else COLLAPSED_HOUR_HEIGHT })
        }
    }
}

/**
 * Every hour any of [events] overlaps — not just the hour each one starts in, so a 09:30–11:30
 * meeting keeps 10:00 at full height too.
 */
fun busyHours(events: List<PositionedEvent>): Set<Int> = buildSet {
    for (event in events) {
        // An event ending exactly on the hour does not occupy that hour.
        val last = ((event.endMinutes - 1) / 60).coerceAtMost(23)
        for (hour in (event.startMinutes / 60)..last) add(hour)
    }
}

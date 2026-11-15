package com.vayunmathur.maps.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * How a departure time reads on the board.
 *
 * A clock time is the wrong answer for a train that is about to leave: "14:32" makes you do the
 * subtraction yourself, on a platform, in a hurry. A countdown is the wrong answer for one this
 * afternoon, because "in 261 minutes" is not a time anybody can plan around. So the board switches
 * between them at [COUNTDOWN_WINDOW_MINUTES].
 */
object DepartureTime {

    /** Inside this many minutes, a departure counts down instead of showing a clock time. */
    const val COUNTDOWN_WINDOW_MINUTES = 10L

    /**
     * Departures further than this from now are not shown at all.
     *
     * A day either side. Further back is history and further forward is next week's timetable,
     * and a board you have to scroll through for a minute is not a board.
     */
    const val WINDOW_HOURS = 24L

    private const val MINUTE_MS = 60_000L

    /** Contract for label. */
    sealed interface Label {
        /** Leaving now — inside half a minute either way. */
        data object Now : Label
        /** Departs in [minutes] (1..[COUNTDOWN_WINDOW_MINUTES]). */
        data class InMinutes(val minutes: Long) : Label
        /** Departed [minutes] ago (1..[COUNTDOWN_WINDOW_MINUTES]). */
        data class MinutesAgo(val minutes: Long) : Label
        /** Outside the countdown window: a clock time, already formatted for the locale. */
        data class Clock(val text: String) : Label
    }

    /**
     * The label for a departure at [departureMillis], as of [nowMillis].
     *
     * Rounded rather than truncated: at 90 seconds "in 2 minutes" is closer to the truth than
     * "in 1 minute", and a board that says 1 minute for a full 120 seconds feels broken. Zero
     * either way is [Label.Now] rather than "in 0 minutes".
     */
    fun label(
        departureMillis: Long,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): Label {
        val deltaMs = departureMillis - nowMillis
        // Magnitude rounded, then the sign reapplied. Rounding the signed value instead would
        // round ties toward positive infinity, so 90 seconds hence reads "in 2 minutes" while 90
        // seconds past reads "1 minute ago" — the same distance from now, labelled differently.
        val minutes = (abs(deltaMs).toDouble() / MINUTE_MS).roundToLong() * (if (deltaMs < 0) -1 else 1)
        return when {
            abs(minutes) > COUNTDOWN_WINDOW_MINUTES -> Label.Clock(clock(departureMillis, zone, locale))
            minutes == 0L -> Label.Now
            minutes > 0 -> Label.InMinutes(minutes)
            else -> Label.MinutesAgo(-minutes)
        }
    }

    /**
     * The locale's own short time format, so a 12-hour locale gets "2:32 PM" and a 24-hour one
     * gets "14:32".
     *
     * `java.time` rather than `android.text.format.DateFormat`, so the whole of this object is
     * testable on the JVM without an emulator. The cost is that it follows the *locale's*
     * convention rather than the user's 24-hour system setting; that setting is an Android-only
     * override and belongs at the call site if it is ever wanted.
     */
    fun clock(millis: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(locale)
            .format(Instant.ofEpochMilli(millis).atZone(zone))

    /** Is this departure inside the day-either-side window the board shows? */
    fun withinWindow(departureMillis: Long, nowMillis: Long): Boolean =
        abs(departureMillis - nowMillis) <= WINDOW_HOURS * 60 * MINUTE_MS

    /**
     * Index of the first departure not yet gone, for the board to scroll to on open.
     *
     * [departures] must be ascending. Returns the size when every departure is in the past, which
     * scrolls to the end — the right place to land, because the most recent departure is the one
     * still worth seeing.
     */
    fun firstUpcoming(departures: List<Long>, nowMillis: Long): Int {
        val at = departures.binarySearch { if (it < nowMillis) -1 else 1 }
        // No exact match is possible with that comparator, so the result is always the insertion
        // point: negative, and -(insertion) - 1.
        return if (at < 0) -at - 1 else at
    }
}

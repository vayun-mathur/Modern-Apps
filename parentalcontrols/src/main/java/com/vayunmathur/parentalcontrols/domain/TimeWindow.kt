package com.vayunmathur.parentalcontrols.domain

/**
 * A recurring daily window stored as minutes past local midnight.
 *
 * Minutes-past-midnight (rather than a timestamp) survives timezone changes and DST the way a
 * user expects: "9pm" means 9pm wherever the device is. [startMinute] greater than [endMinute]
 * is the normal case, not an error - a window that crosses midnight.
 *
 * Bedtime, downtime and school time are three answers to one question - when may apps be used -
 * so they share this predicate while keeping separate rows (separate on/off, times and days).
 */
data class TimeWindow(
    val enabled: Boolean = false,
    /** Minutes past local midnight at which the window opens. */
    val startMinute: Int = 0,
    /** Minutes past local midnight at which it closes. */
    val endMinute: Int = 0,
    /** Bitmask of days the window applies to, bit 0 = Monday. */
    val daysMask: Int = ALL_DAYS,
) {
    /** Whether [minuteOfDay] on [dayIndex] (0 = Monday) falls inside the window. */
    fun contains(dayIndex: Int, minuteOfDay: Int): Boolean {
        if (!enabled) return false
        return if (startMinute <= endMinute) {
            // Same-day window.
            isDaySet(dayIndex) && minuteOfDay >= startMinute && minuteOfDay < endMinute
        } else {
            // Crosses midnight. The evening half belongs to `dayIndex`; the morning half belongs
            // to the *previous* day's window, so that "Fri 21:00-07:00" still applies at 02:00
            // on Saturday.
            (isDaySet(dayIndex) && minuteOfDay >= startMinute) ||
                (isDaySet((dayIndex + 6) % DAYS_IN_WEEK) && minuteOfDay < endMinute)
        }
    }

    fun isDaySet(dayIndex: Int): Boolean = (daysMask shr dayIndex) and 1 == 1

    /** Shared day and minute constants for recurring daily windows. */
    companion object {
        const val DAYS_IN_WEEK = 7
        const val ALL_DAYS = 0b111_1111
        const val WEEKDAYS = 0b001_1111
        const val MINUTES_PER_DAY = 24 * 60
    }
}

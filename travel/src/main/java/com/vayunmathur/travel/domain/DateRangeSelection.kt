package com.vayunmathur.travel.domain

import java.time.LocalDate

/**
 * The next (start, end) selection after tapping [tapped] in a range calendar,
 * following Material's range rules: a tap with no range in progress (or after
 * a complete range) starts a new range; a tap before the start restarts there;
 * otherwise it ends the range. [allowSameDay] = false (hotel nights) keeps
 * waiting for an end after the start when the start is tapped again.
 */
fun nextRange(start: LocalDate?, end: LocalDate?, tapped: LocalDate, allowSameDay: Boolean): Pair<LocalDate?, LocalDate?> =
    when {
        start == null || end != null -> tapped to null
        tapped.isBefore(start) -> tapped to null
        tapped == start && !allowSameDay -> start to null
        else -> start to tapped
    }

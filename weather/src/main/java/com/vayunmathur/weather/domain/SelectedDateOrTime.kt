package com.vayunmathur.weather.domain

/** Contract for selected date or time. */
sealed interface SelectedDateOrTime {
    /** Immutable value describing time; carries isoTime. */
    data class Time(val isoTime: String) : SelectedDateOrTime
    /** Immutable value describing day; carries isoDate. */
    data class Day(val isoDate: String) : SelectedDateOrTime
}

package com.vayunmathur.clock

import com.vayunmathur.library.util.NavKey
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable

/** Navigation destinations for the clock app: sibling tab routes and their dialogs. */
@Serializable
sealed interface Route : NavKey {
    /** The alarm tab destination. */
    @Serializable
    data object Alarm : Route
    /** The world-clock tab destination. */
    @Serializable
    data object Clock : Route
    /** The timer tab destination. */
    @Serializable
    data object Timer : Route
    /** The stopwatch tab destination. */
    @Serializable
    data object Stopwatch : Route
    /** The alarm-settings destination. */
    @Serializable
    data object AlarmSettings : Route
    /** Dialog for choosing world-clock time zones. */
    @Serializable
    data object SelectTimeZonesDialog : Route
    /** Dialog to create a timer, optionally prefilled with a length and label. */
    @Serializable
    data class NewTimerDialog(val lengthSeconds: Int? = null, val message: String? = null) : Route
    /** Dialog to create an alarm, optionally prefilled and optionally skipping the UI. */
    @Serializable
    data class NewAlarmDialog(
        val hour: Int? = null,
        val minutes: Int? = null,
        val message: String? = null,
        val days: ArrayList<Int>? = null,
        val skipUi: Boolean = false,
    ) : Route
    /** Dialog to set the time of the alarm identified by [id] to [time]. */
    @Serializable
    data class AlarmSetTimeDialog(val id: Long, val time: LocalTime) : Route
}

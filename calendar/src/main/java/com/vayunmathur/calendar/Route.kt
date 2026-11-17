package com.vayunmathur.calendar

import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.calendar.domain.RecurrenceParams
import com.vayunmathur.library.util.NavKey
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable

/** Navigation keys for calendar screens and dialogs, serializable so the back stack can be restored. */
sealed interface Route: NavKey {
    @Serializable
    /** Calendar home destination. */
    data object Calendar: Route {
        @Serializable
        /** Go-to-date dialog over the calendar home, initially showing the given viewing date. */
        data class GotoDialog(val dateViewing: LocalDate): Route
    }

    @Serializable
    /** Settings destination and parent of the settings dialog destinations. */
    data object Settings: Route {
        @Serializable
        /** Colour-picker dialog for the calendar with the given id. */
        data class ChangeColor(val id: Long): Route

        @Serializable
        /** Dialog for creating a new local calendar. */
        data class AddCalendar(val placeholder: Int = 0): Route

        @Serializable
        /** Dialog for renaming the calendar with the given id. */
        data class RenameCalendar(val id: Long): Route

        @Serializable
        /** Confirmation dialog for deleting the calendar with the given id. */
        data class DeleteCalendar(val id: Long): Route

        @Serializable
        /** Dialog for editing default reminders of the calendar with the given id. */
        data class DefaultReminders(val id: Long): Route

        @Serializable
        /** Holiday-calendar picker destination under Settings. */
        data object HolidayCalendars: Route

        @Serializable
        /** ICS import destination for the files at the given URIs. */
        data class ImportIcs(val uris: List<String>): Route
    }

    @Serializable
    /** Event detail destination for one occurrence of an event. */
    data class Event(val instance: Instance): Route

    @Serializable
    /** Event create/edit destination; a null id creates a new event, otherwise the event with that id is edited. */
    data class EditEvent(
        val id: Long?,
        val title: String? = null,
        val description: String? = null,
        val location: String? = null,
        val beginTime: Long? = null,
        val endTime: Long? = null,
        val allDay: Boolean? = null,
        /**
         * The instance the user opened, when they arrived here from its detail screen.
         *
         * The title morph needs it: a title is keyed per *instance* so that a recurring event
         * showing several times on the grid still has one origin per key, while everything else
         * about an edit is keyed per event. Null when the edit was not reached from a detail screen.
         */
        val instanceId: Long? = null,
    ): Route {
        @Serializable
        /** Date-picker dialog that returns its result under its key, starting at the initial date. */
        data class DatePickerDialog(val key: String, val initialDate: LocalDate, val minDate: LocalDate? = null): Route

        @Serializable
        /** Time-picker dialog that returns its result under its key, starting at the initial time. */
        data class TimePickerDialog(val key: String, val initialTime: LocalTime, val minTime: LocalTime? = null): Route

        @Serializable
        /** Calendar-picker dialog that returns its result under its key. */
        data class CalendarPickerDialog(val key: String): Route
        @Serializable
        /** Timezone-picker dialog that returns its result under its key. */
        data class TimezonePickerDialog(val key: String): Route

        @Serializable
        /** Recurrence-editor dialog returning its result under its key for an event starting on the start date. */
        data class RecurrenceDialog(
            val key: String,
            val startDate: LocalDate,
            val initial: RecurrenceParams? = null,
            val initialDates: List<LocalDate> = emptyList(),
        ): Route
    }
}

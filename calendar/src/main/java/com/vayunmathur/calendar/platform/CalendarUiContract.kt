package com.vayunmathur.calendar.platform

import com.vayunmathur.calendar.data.Calendar
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.data.Instance
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * The UI contract between [CalendarViewModel] and the three screens the store listing is
 * shot from (calendar, event detail, settings).
 *
 * Those screens take a state value plus an actions interface rather than the ViewModel
 * itself, so they can be rendered by a `@Preview` — which is what the listing images are
 * generated from. It lives in `platform/` rather than `ui/` so the dependency runs one way:
 * `ui` depends on `platform`, and the ViewModel implements these interfaces.
 *
 * The "close" callbacks below are deliberately named per screen. The ViewModel implements
 * every interface here, and two same-named defaults inherited from different interfaces
 * would have to be resolved in the ViewModel itself.
 */

/** Everything the calendar screen draws. */
data class CalendarUiState(
    val layout: CalendarViewModel.CalendarLayout = CalendarViewModel.CalendarLayout.FullWeek,
    /** The date the user is looking at; drives the month/year title and the pager position. */
    val dateViewing: LocalDate,
    /** Today, which is also the anchor the pagers count pages from. */
    val today: LocalDate,
    val events: List<Event> = emptyList(),
    val calendars: Map<Long, Calendar> = emptyMap(),
    val calendarVisibility: Map<Long, Boolean> = emptyMap(),
    /**
     * Pre-resolved instances for the month grid, bypassing [CalendarActions.visibleInstances].
     * Only a preview sets this: the provider query runs in a coroutine, and a preview is a
     * single static composition where effects never run, so a month view built the normal
     * way would render empty.
     */
    val previewInstances: List<Instance>? = null,
)

/** Everything the event detail screen draws. */
data class EventUiState(
    val event: Event,
    val calendar: Calendar,
    val instance: Instance,
)

/** Everything the settings screen draws. */
data class SettingsUiState(
    val calendars: List<Calendar> = emptyList(),
    val calendarVisibility: Map<Long, Boolean> = emptyMap(),
    val layout: CalendarViewModel.CalendarLayout = CalendarViewModel.CalendarLayout.FullWeek,
    val themeMode: CalendarViewModel.ThemeMode = CalendarViewModel.ThemeMode.System,
)

/**
 * Calendar screen callbacks. Every method has a no-op default so a preview can render the
 * screen without supplying behaviour — [Noop] is the whole implementation a preview needs.
 * The `open*` methods navigate, so they are supplied by the screen's binder rather than by
 * the ViewModel, which has no back stack.
 */
interface CalendarActions {
    fun setLayout(layout: CalendarViewModel.CalendarLayout) {}
    fun setSelectedDate(date: LocalDate) {}

    /** Instances between [start] and [end] from the visible calendars. */
    suspend fun visibleInstances(start: Instant, end: Instant): List<Instance> = emptyList()

    fun openDatePicker(date: LocalDate) {}
    fun openSettings() {}
    fun openEvent(instance: Instance) {}
    fun createEvent() {}

    /** Create a new event pre-filled to [date] (e.g. from a long-press on a month cell). */
    fun createEventOn(date: LocalDate) {}

    /** No-op calendar actions used by previews, which render the screen without behaviour. */
    companion object {
        val Noop: CalendarActions = object : CalendarActions {}
    }
}

/** Event detail callbacks. Same no-op-default arrangement as [CalendarActions]. */
interface EventActions {
    fun closeEvent() {}
    fun editEvent(eventId: Long) {}
    fun deleteEventSeries(eventId: Long) {}
    fun deleteEventInstance(eventId: Long, instanceBeginTime: Long) {}

    /** No-op event-detail actions used by previews, which render the screen without behaviour. */
    companion object {
        val Noop: EventActions = object : EventActions {}
    }
}

/** Settings callbacks. Same no-op-default arrangement as [CalendarActions]. */
interface SettingsActions {
    fun setLayout(layout: CalendarViewModel.CalendarLayout) {}
    fun setThemeMode(mode: CalendarViewModel.ThemeMode) {}
    fun setCalendarVisibility(calendarId: Long, visible: Boolean) {}

    fun closeSettings() {}
    fun openAddCalendar() {}
    fun openRenameCalendar(calendarId: Long) {}
    fun openDeleteCalendar(calendarId: Long) {}
    fun openChangeColor(calendarId: Long) {}
    fun openDefaultReminders(calendarId: Long) {}
    fun openHolidayCalendars() {}

    /** Opens the system file picker; the picked files are handed to the import screen. */
    fun importIcs() {}

    /** Opens the system file creator, then writes every visible calendar's events to it. */
    fun exportIcs() {}

    /** Same, restricted to one calendar. Read-only calendars are exportable too. */
    fun exportCalendarIcs(calendarId: Long) {}

    /** No-op settings actions used by previews, which render the screen without behaviour. */
    companion object {
        val Noop: SettingsActions = object : SettingsActions {}
    }
}

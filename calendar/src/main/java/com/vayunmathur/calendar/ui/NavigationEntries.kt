package com.vayunmathur.calendar.ui

import com.vayunmathur.calendar.Route
import com.vayunmathur.calendar.platform.CalendarViewModel
import com.vayunmathur.calendar.ui.dialogs.CalendarPickerDialog
import com.vayunmathur.calendar.ui.dialogs.CalendarSetDateDialog
import com.vayunmathur.calendar.ui.dialogs.RecurrenceDialog
import com.vayunmathur.calendar.ui.dialogs.SettingsAddCalendarDialog
import com.vayunmathur.calendar.ui.dialogs.SettingsChangeColorDialog
import com.vayunmathur.calendar.ui.dialogs.SettingsDefaultRemindersDialog
import com.vayunmathur.calendar.ui.dialogs.SettingsDeleteCalendarDialog
import com.vayunmathur.calendar.ui.dialogs.SettingsRenameCalendarDialog
import com.vayunmathur.calendar.ui.dialogs.TimezonePickerDialog
import com.vayunmathur.library.ui.dialog.DatePickerDialog
import com.vayunmathur.library.ui.dialog.TimePickerDialogContent
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.EntryProviderScope
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.NavBackStack

internal fun EntryProviderScope<Route>.mainEntries(
    viewModel: CalendarViewModel,
    backStack: NavBackStack<Route>,
) {
    entry<Route.Calendar>(metadata = ListPage()) {
        CalendarScreen(viewModel, backStack)
    }
    // Morph: the event's title travels out of the chip the user tapped on the grid.
    entry<Route.Event>(metadata = ListDetailPage() + MorphPage()) { key ->
        EventScreen(viewModel, key.instance, backStack)
    }
    entry<Route.Settings> {
        SettingsScreen(viewModel, backStack)
    }
    entry<Route.Settings.HolidayCalendars> {
        HolidayCalendarsScreen(viewModel, backStack)
    }
    // Morph: the event's location line grows into the location field.
    entry<Route.EditEvent>(metadata = ListDetailPage() + MorphPage()) { key ->
        EditEventScreen(viewModel, key, backStack)
    }
    entry<Route.Settings.ImportIcs> { key ->
        ImportIcsScreen(viewModel, backStack, key.uris)
    }
}

internal fun EntryProviderScope<Route>.dialogEntries(
    backStack: NavBackStack<Route>,
) {
    entry<Route.Calendar.GotoDialog>(metadata = DialogPage()) { key ->
        CalendarSetDateDialog(backStack, key.dateViewing)
    }

    entry<Route.EditEvent.DatePickerDialog>(metadata = DialogPage()) { key ->
        DatePickerDialog(backStack, key.key, key.initialDate, key.minDate)
    }

    entry<Route.EditEvent.TimePickerDialog>(metadata = DialogPage()) { key ->
        TimePickerDialogContent(backStack, key.key, key.initialTime, key.minTime)
    }

    entry<Route.EditEvent.CalendarPickerDialog>(metadata = DialogPage()) { key ->
        CalendarPickerDialog(backStack, key.key)
    }

    entry<Route.EditEvent.TimezonePickerDialog>(metadata = DialogPage()) { key ->
        TimezonePickerDialog(backStack, key.key)
    }

    entry<Route.EditEvent.RecurrenceDialog>(metadata = DialogPage()) { key ->
        RecurrenceDialog(backStack, key.key, key.startDate, key.initial, key.initialDates)
    }
}

internal fun EntryProviderScope<Route>.settingsDialogEntries(
    viewModel: CalendarViewModel,
    backStack: NavBackStack<Route>,
) {
    entry<Route.Settings.ChangeColor>(metadata = DialogPage()) { key ->
        SettingsChangeColorDialog(viewModel, backStack, key.id)
    }

    entry<Route.Settings.AddCalendar>(metadata = DialogPage()) { _ ->
        SettingsAddCalendarDialog(viewModel, backStack)
    }

    entry<Route.Settings.RenameCalendar>(metadata = DialogPage()) { key ->
        SettingsRenameCalendarDialog(viewModel, backStack, key.id)
    }

    entry<Route.Settings.DeleteCalendar>(metadata = DialogPage()) { key ->
        SettingsDeleteCalendarDialog(viewModel, backStack, key.id)
    }

    entry<Route.Settings.DefaultReminders>(metadata = DialogPage()) { key ->
        SettingsDefaultRemindersDialog(viewModel, backStack, key.id)
    }
}

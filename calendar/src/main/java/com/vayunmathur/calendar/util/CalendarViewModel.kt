package com.vayunmathur.calendar.util
import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract
import com.vayunmathur.library.log.Log
import androidx.annotation.StringRes
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.glance.CalendarGlanceWidget
import com.vayunmathur.calendar.glance.CalendarMonthGlanceWidget
import com.vayunmathur.calendar.ui.parseICSFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.data.Calendar
import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.calendar.util.toIcalBasic
import com.vayunmathur.calendar.util.toIcalUtcDateTime

import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.AppMessages

/**
 * Implements the screens' actions interfaces (see `CalendarUiContract`) so a binder can
 * hand itself straight to a stateless screen; the navigating members keep their no-op
 * defaults and are overridden per screen, since the ViewModel has no back stack.
 */
class CalendarViewModel(application: Application) :
    AndroidViewModel(application), CalendarActions, EventActions, SettingsActions {
    val dataStore = DataStoreUtils.getInstance(application)

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events.asStateFlow()

    private val _calendars = MutableStateFlow<List<Calendar>>(emptyList())
    val calendars: StateFlow<List<Calendar>> = _calendars.asStateFlow()

    // map calendarId -> visible (whether to render events from that calendar)
    private val _calendarVisibility = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    val calendarVisibility: StateFlow<Map<Long, Boolean>> = _calendarVisibility.asStateFlow()

    private val _lastViewedDate = MutableStateFlow<LocalDate?>(null)
    val lastViewedDate: StateFlow<LocalDate?> = _lastViewedDate.asStateFlow()

    enum class CalendarLayout(@StringRes val shortNameRes: Int, @StringRes val prettyNameRes: Int) {
        Agenda(R.string.layout_short_agenda, R.string.layout_agenda),
        Day(R.string.layout_short_day, R.string.layout_day),
        WorkWeek(R.string.layout_short_work_week, R.string.layout_work_week),
        FullWeek(R.string.layout_short_full_week, R.string.layout_full_week),
        Month(R.string.layout_short_month, R.string.layout_month),
        MonthAgenda(R.string.layout_short_month_agenda, R.string.layout_month_agenda),
        WorkWeekSummary(R.string.layout_short_work_week_summary, R.string.layout_work_week_summary),
        FullWeekSummary(R.string.layout_short_full_week_summary, R.string.layout_full_week_summary),
        WorkWeekCompact(R.string.layout_short_work_week_compact, R.string.layout_work_week_compact),
        FullWeekCompact(R.string.layout_short_full_week_compact, R.string.layout_full_week_compact)
    }

    private val _currentLayout = MutableStateFlow(
        dataStore.getString("default_calendar_layout")
            ?.let { runCatching { CalendarLayout.valueOf(it) }.getOrNull() }
            ?: CalendarLayout.FullWeek
    )
    val currentLayout: StateFlow<CalendarLayout> = _currentLayout.asStateFlow()

    override fun setLayout(layout: CalendarLayout) {
        _currentLayout.value = layout
        viewModelScope.launch {
            dataStore.setString("default_calendar_layout", layout.name)
        }
    }

    enum class ThemeMode(@StringRes val prettyNameRes: Int) {
        System(R.string.theme_system_default),
        Light(R.string.theme_light),
        Dark(R.string.theme_dark),
    }

    private val _themeMode = MutableStateFlow(
        dataStore.getString("theme_mode")
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.System
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    override fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        viewModelScope.launch {
            dataStore.setString("theme_mode", mode.name)
        }
    }

    // The last calendar the user actively picked in the event calendar picker.
    // Used as the default selection when creating a new event.
    fun getDefaultCalendarId(): Long? = dataStore.getLong("last_selected_calendar_id")

    fun setDefaultCalendar(id: Long) {
        viewModelScope.launch {
            dataStore.setLong("last_selected_calendar_id", id)
        }
    }

    /** Default reminders (minutes before start) applied to new events in calendar [calendarId]. */
    fun getDefaultReminders(calendarId: Long): List<Int> =
        dataStore.getString("default_reminders_$calendarId")
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?: emptyList()

    /**
     * Persist the per-calendar default reminders. When [applyToExisting] is true the same set is
     * written onto every event already in that calendar; otherwise only new events pick it up.
     */
    fun setDefaultReminders(calendarId: Long, minutes: List<Int>, applyToExisting: Boolean) {
        val normalized = minutes.distinct().sorted()
        viewModelScope.launch {
            dataStore.setString("default_reminders_$calendarId", normalized.joinToString(","))
            if (!applyToExisting) return@launch
            withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                val affected = _events.value.filter { it.calendarID == calendarId && it.id != null }
                affected.forEach { ev ->
                    app.contentResolver.writeReminders(ev.id!!, normalized)
                    try {
                        app.contentResolver.update(
                            CalendarContract.Events.CONTENT_URI,
                            ContentValues().apply {
                                put(CalendarContract.Events.HAS_ALARM, if (normalized.isEmpty()) 0 else 1)
                            },
                            "${CalendarContract.Events._ID} = ?",
                            arrayOf(ev.id.toString()),
                        )
                    } catch (expected: Exception) {
                        Log.error("CalendarViewModel", "Error applying default reminders", expected)
                    }
                }
                _events.value = Event.getAllEvents(app)
                ReminderScheduler.reconcileAll(app, _events.value)
                updateWidgets()
            }
        }
    }

    fun setLastViewedDate(d: LocalDate?) {
        _lastViewedDate.value = d
    }

    // Currently selected/viewed date in the calendar UI. Always starts at today
    // so the user sees the current week on launch. Navigation within the app
    // updates this in-memory; persistence to DataStore is performed explicitly
    // via [setLastViewedDate] at navigation transitions.
    private val _selectedDate = MutableStateFlow<LocalDate>(
        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    )
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    override fun setSelectedDate(date: LocalDate) {
        _selectedDate.value = date
    }

    // Parsed-ICS state for the import dialog. null = not yet parsed (or cleared);
    // empty list = parsed and found nothing; non-empty = parsed events ready to import.
    private val _parsedIcsEvents = MutableStateFlow<List<Event>?>(null)
    val parsedIcsEvents: StateFlow<List<Event>?> = _parsedIcsEvents.asStateFlow()

    /** Parses every [uris] off the main thread and exposes the result via [parsedIcsEvents]. */
    fun parseIcsUris(uris: List<Uri>) {
        if (uris.isEmpty()) {
            _parsedIcsEvents.value = emptyList()
            return
        }
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val allEvents = mutableListOf<Event>()
            uris.forEach { uri ->
                try {
                    app.contentResolver.openInputStream(uri)?.use { iS ->
                        allEvents.addAll(parseICSFile(iS))
                    }
                } catch (expected: Exception) {
                    Log.error("CalendarViewModel", "Error parsing ICS file: $uri", expected)
                }
            }
            _parsedIcsEvents.value = allEvents
        }
    }

    /** Clears any parsed-ICS state held in the VM (called when the import dialog dismisses). */
    fun clearParsedIcs() {
        _parsedIcsEvents.value = null
    }

    /**
     * Inserts the previously parsed [events] into the calendar with id [calendarId].
     * Runs off the main thread; invokes [onDone] on the main thread when complete (or on failure).
     *
     * Events are inserted one by one rather than via `bulkInsert` because `bulkInsert`
     * returns only a count, not the new row ids — and reminders live in
     * `CalendarContract.Reminders`, which needs the event id. Each event's
     * [Event.reminders] (from VALARM triggers on the import path) are written
     * explicitly and `HAS_ALARM` is set, mirroring [upsertEvent].
     */
    fun importIcsEvents(
        events: List<Event>,
        calendarId: Long,
        onDone: () -> Unit = {},
    ) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    for (event in events) {
                        val newUri = app.contentResolver.insert(
                            CalendarContract.Events.CONTENT_URI,
                            event.toContentValues(calendarId).apply {
                                put(
                                    CalendarContract.Events.HAS_ALARM,
                                    if (event.reminders.isEmpty()) 0 else 1,
                                )
                            },
                        )
                        val newId = newUri?.lastPathSegment?.toLongOrNull()
                        if (newId != null && event.reminders.isNotEmpty()) {
                            app.contentResolver.writeReminders(newId, event.reminders)
                        }
                    }
                    _events.value = Event.getAllEvents(app)
                    ReminderScheduler.reconcileAll(app, _events.value)
                } catch (expected: Exception) {
                    Log.error("CalendarViewModel", "Error importing events", expected)
                }
            }
            updateWidgets()
            onDone()
        }
    }

    /**
     * Writes the app's events to [uri] as RFC 5545. A [calendarId] limits the export to that one
     * calendar; without it every currently-visible calendar is included.
     */
    fun exportIcs(uri: Uri, calendarId: Long? = null) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val message = try {
                val visible = _calendarVisibility.value
                val events = Event.getAllEvents(app).filter { event ->
                    if (calendarId != null) event.calendarID == calendarId
                    else visible[event.calendarID] != false
                }
                app.contentResolver.openOutputStream(uri)?.use { out ->
                    out.bufferedWriter().use { writer -> writeIcs(events, writer) }
                } ?: error("Could not open $uri for writing")
                app.getString(R.string.export_ics_success)
            } catch (expected: Exception) {
                Log.error("CalendarViewModel", "Error exporting ICS to $uri", expected)
                app.getString(R.string.export_ics_failed_format, expected.message ?: expected.javaClass.simpleName)
            }
            withContext(Dispatchers.Main) { AppMessages.show(message) }
        }
    }

    fun loadData() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            _events.value = Event.getAllEvents(app)

            val loaded = Calendar.getAllCalendars(app)
            _calendars.value = loaded

            // initialize visibility from provider's VISIBLE flag
            val visMap = loaded.associate { cal -> cal.id to cal.visible }
            _calendarVisibility.value = visMap
        }
    }

    init {
        loadData()
        viewModelScope.launch {
            dataStore.stringFlow("default_calendar_layout").collect { saved ->
                runCatching { CalendarLayout.valueOf(saved) }.onSuccess { _currentLayout.value = it }
            }
        }
        viewModelScope.launch {
            dataStore.stringFlow("theme_mode").collect { saved ->
                runCatching { ThemeMode.valueOf(saved) }.onSuccess { _themeMode.value = it }
            }
        }
    }

    fun updateWidgets() {
        viewModelScope.launch {
            CalendarGlanceWidget().updateAll(getApplication())
            CalendarMonthGlanceWidget().updateAll(getApplication())
        }
    }

    /**
     * Loads calendar instances between [start] and [end] off the main thread, filtered to
     * the currently-loaded events whose calendar is visible. The UI consumes this via
     * produceState so no ContentResolver query happens during composition.
     */
    override suspend fun visibleInstances(start: Instant, end: Instant): List<Instance> =
        withContext(Dispatchers.IO) {
            val app = getApplication<Application>()
            val eventsById = _events.value.associateBy { it.id }
            val visibility = _calendarVisibility.value
            Instance.getInstances(app, start, end)
                .filter { it.eventID in eventsById }
                .filter { visibility[eventsById[it.eventID]!!.calendarID] ?: true }
        }

    private suspend fun refreshCalendarsAndWidgets() {
        val app = getApplication<Application>()
        val loaded = Calendar.getAllCalendars(app)
        _calendars.value = loaded
        _calendarVisibility.value = loaded.associate { it.id to it.visible }
        updateWidgets()
    }

    /** Reload calendars and events from the provider (e.g. after holiday calendars change). */
    fun reloadAll() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val loaded = Calendar.getAllCalendars(app)
            _calendars.value = loaded
            _calendarVisibility.value = loaded.associate { it.id to it.visible }
            _events.value = Event.getAllEvents(app)
            updateWidgets()
        }
    }

    override fun setCalendarVisibility(calendarId: Long, visible: Boolean) {
        val app = getApplication<Application>()
        // write to the provider's Calendars.VISIBLE field for that calendar
        val values = ContentValues().apply { put(CalendarContract.Calendars.VISIBLE, if (visible) 1 else 0) }
        val uri = CalendarContract.Calendars.CONTENT_URI
        viewModelScope.launch(Dispatchers.IO) {
            try {
                app.contentResolver.update(
                    uri,
                    values,
                    "${CalendarContract.Calendars._ID} = ?",
                    arrayOf(calendarId.toString()),
                )
            } catch (expected: Exception) {
                Log.error("CalendarViewModel", "Error setting calendar visibility", expected)
            }
            refreshCalendarsAndWidgets()
        }
    }

    /**
     * Deletes the whole series for [eventId] from the provider.
     *
     * Imported and recurring events reported undeletable (#825): marking DELETED only asked
     * a sync adapter to delete later, which for a local calendar never comes. A real
     * `delete()` removes the row now; the DELETED-flag update stays as the fallback when
     * the provider refuses it.
     */
    override fun deleteEventSeries(eventId: Long) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val deleted = try {
                app.contentResolver.delete(
                    CalendarContract.Events.CONTENT_URI,
                    "${CalendarContract.Events._ID} = ?",
                    arrayOf(eventId.toString()),
                )
            } catch (expected: Exception) {
                Log.error("CalendarViewModel", "Error deleting event", expected)
                0
            }
            if (deleted > 0) {
                _events.value = Event.getAllEvents(app)
                ReminderScheduler.reconcileAll(app, _events.value)
                updateWidgets()
            } else {
                // The provider refused the delete (e.g. a read-only calendar it would not
                // remove): flag it instead so the row at least hides from our queries.
                upsertEvent(eventId, ContentValues().apply {
                    put(CalendarContract.Events.DELETED, 1)
                })
                withContext(Dispatchers.Main) {
                    AppMessages.show(app.getString(R.string.event_delete_failed))
                }
            }
        }
    }

    /**
     * Removes one occurrence starting at [instanceBeginTime] from the series for [eventId].
     *
     * RRULE occurrences are excluded via EXDATE; RDATE occurrences live in the event's own
     * date list instead, so those are stripped there. A non-series event has no occurrence
     * to cut out, so deleting its "instance" deletes the event.
     */
    override fun deleteEventInstance(eventId: Long, instanceBeginTime: Long) {
        val event = _events.value.find { it.id == eventId } ?: return
        if (!event.isRecurring) {
            deleteEventSeries(eventId)
            return
        }
        val zone = if (event.allDay) TimeZone.UTC else TimeZone.of(event.timezone)
        val instanceDate = Instant.fromEpochMilliseconds(instanceBeginTime)
            .toLocalDateTime(zone).date
        if (event.rdate.contains(instanceDate)) {
            val updated = event.withoutRdate(instanceDate)
            if (updated.isRecurring) {
                upsertEvent(eventId, ContentValues().apply {
                    put(CalendarContract.Events.RDATE, updated.rdateColumnValue())
                })
            } else {
                // That was the last extra date: the event is a one-off now, so
                // write the full row. A column-only RDATE clear would leave the
                // recurring row's DTEND=null/DURATION behind, and a
                // non-recurring event without DTEND expands to no instances -
                // the remaining event would vanish from every view.
                upsertEvent(eventId, updated.toContentValues(updated.calendarID))
            }
            return
        }
        // Timed occurrences need the datetime form; a date-only EXDATE is ignored
        // by the provider for timed events and the delete silently no-ops.
        val exdateStr = (event.exdate + instanceDate).distinct().joinToString(",") { date ->
            if (event.allDay) date.toIcalBasic()
            else date.toIcalUtcDateTime(event.startDateTimeDisplay.time, zone)
        }
        upsertEvent(eventId, ContentValues().apply {
            put(CalendarContract.Events.EXDATE, exdateStr)
        })
    }

    // Insert or update event using ContentValues. If eventId is null -> insert, otherwise update.
    // When [reminders] is non-null, the event's reminders are replaced with the given
    // minutes-before list (and HAS_ALARM is set accordingly). Runs off the main thread.
    fun upsertEvent(eventId: Long?, values: ContentValues, reminders: List<Int>? = null) {
        val app = getApplication<Application>()
        val uri = CalendarContract.Events.CONTENT_URI
        if (reminders != null) {
            values.put(CalendarContract.Events.HAS_ALARM, if (reminders.isEmpty()) 0 else 1)
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (eventId == null) {
                    val newUri = app.contentResolver.insert(uri, values)
                    val newId = newUri?.lastPathSegment?.toLongOrNull()
                    if (newId != null && reminders != null) app.contentResolver.writeReminders(newId, reminders)
                } else {
                    app.contentResolver.update(
                        uri,
                        values,
                        "${CalendarContract.Events._ID} = ?",
                        arrayOf(eventId.toString()),
                    )
                    if (reminders != null) app.contentResolver.writeReminders(eventId, reminders)
                }
                _events.value = Event.getAllEvents(app)
                ReminderScheduler.reconcileAll(app, _events.value)
                updateWidgets()
            } catch (expected: Exception) {
                Log.error("CalendarViewModel", "Error upserting event", expected)
            }
        }
    }


    // set the calendar color in the provider and refresh cached calendars
    fun setCalendarColor(calendarId: Long, colorInt: Int) {
        val app = getApplication<Application>()
        val values = ContentValues().apply { put(CalendarContract.Calendars.CALENDAR_COLOR, colorInt) }
        val uri = CalendarContract.Calendars.CONTENT_URI
        viewModelScope.launch(Dispatchers.IO) {
            try {
                app.contentResolver.update(
                    uri,
                    values,
                    "${CalendarContract.Calendars._ID} = ?",
                    arrayOf(calendarId.toString()),
                )
            } catch (expected: Exception) {
                Log.error("CalendarViewModel", "Error setting calendar color", expected)
            }
            refreshCalendarsAndWidgets()
        }
    }

    // rename

    // rename a calendar's display name
    fun renameCalendar(calendarId: Long, newDisplayName: String) {
        val app = getApplication<Application>()
        val cal = calendars.value.find { it.id == calendarId }
        if (cal == null || !cal.canModify) {
            Log.error("CalendarViewModel", "Attempted to rename a readonly or non-existent calendar")
            return
        }
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, newDisplayName)
            put(CalendarContract.Calendars.NAME, newDisplayName)
        }
        val uri = CalendarContract.Calendars.CONTENT_URI
        viewModelScope.launch(Dispatchers.IO) {
            try {
                app.contentResolver.update(
                    uri,
                    values,
                    "${CalendarContract.Calendars._ID} = ?",
                    arrayOf(calendarId.toString()),
                )
            } catch (expected: Exception) {
                Log.error("CalendarViewModel", "Error renaming calendar", expected)
            }
            refreshCalendarsAndWidgets()
        }
    }

    // delete a calendar and refresh caches
    fun deleteCalendar(calendarId: Long) {
        val app = getApplication<Application>()
        val cal = calendars.value.find { it.id == calendarId }
        if (cal == null || !cal.canModify) {
            Log.error("CalendarViewModel", "Attempted to delete a readonly or non-existent calendar")
            return
        }
        val uri = CalendarContract.Calendars.CONTENT_URI
        viewModelScope.launch(Dispatchers.IO) {
            try {
                app.contentResolver.delete(uri, "${CalendarContract.Calendars._ID} = ?", arrayOf(calendarId.toString()))
            } catch (expected: Exception) {
                Log.error("CalendarViewModel", "Error deleting calendar", expected)
            }
            refreshCalendarsAndWidgets()
        }
    }

    // create a new local/offline calendar in the provider and refresh caches
    // onComplete callback receives the new calendar ID (or null on failure) on main thread
    fun createLocalCalendar(
        accountName: String,
        displayName: String,
        colorInt: Int,
        visible: Boolean,
        accessLevel: Int,
        onComplete: ((Long?) -> Unit)? = null,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()

            // To insert calendars with custom account fields we need to use the sync adapter flag
            val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                .build()

            val values = ContentValues().apply {
                put(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
                put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                put(CalendarContract.Calendars.OWNER_ACCOUNT, accountName)
                put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, displayName)
                put(CalendarContract.Calendars.NAME, displayName)
                put(CalendarContract.Calendars.CALENDAR_COLOR, colorInt)
                put(CalendarContract.Calendars.VISIBLE, if (visible) 1 else 0)
                put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, accessLevel)
                put(CalendarContract.Calendars.SYNC_EVENTS, 1)
                put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.currentSystemDefault().id)
            }

            var newId: Long? = null
            try {
                val resultUri = app.contentResolver.insert(uri, values)
                newId = resultUri?.lastPathSegment?.toLongOrNull()
            } catch (expected: Exception) {
                Log.error("CalendarViewModel", "Error creating local calendar", expected)
            }
            refreshCalendarsAndWidgets()
            withContext(Dispatchers.Main) {
                onComplete?.invoke(newId)
            }
        }
    }

}

/** Replace all reminders for [eventId] with [reminders] (minutes before start). */
internal fun android.content.ContentResolver.writeReminders(eventId: Long, reminders: List<Int>) {
    try {
        delete(
            CalendarContract.Reminders.CONTENT_URI,
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
        )
        reminders.distinct().forEach { minutes ->
            insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, minutes)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            })
        }
    } catch (expected: Exception) {
        Log.error("CalendarViewModel", "Error writing reminders", expected)
    }
}

package com.vayunmathur.calendar.util
import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.calendar.glance.CalendarGlanceWidget
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

import com.vayunmathur.library.util.DataStoreUtils

class CalendarViewModel(application: Application) : AndroidViewModel(application) {
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

    enum class CalendarLayout(val shortName: String, val prettyName: String) {
        Agenda("A", "Agenda"),
        Day("D", "Day"),
        WorkWeek("W5", "Work Week"),
        FullWeek("W7", "Full Week"),
        Month("M", "Month"),
        WorkWeekSummary("W5S", "Work Week Summary"),
        FullWeekSummary("W7S", "Full Week Summary")
    }

    private val _currentLayout = MutableStateFlow(
        dataStore.getString("default_calendar_layout")
            ?.let { runCatching { CalendarLayout.valueOf(it) }.getOrNull() }
            ?: CalendarLayout.FullWeek
    )
    val currentLayout: StateFlow<CalendarLayout> = _currentLayout.asStateFlow()

    fun setLayout(layout: CalendarLayout) {
        _currentLayout.value = layout
        viewModelScope.launch {
            dataStore.setString("default_calendar_layout", layout.name)
        }
    }

    enum class ThemeMode(val prettyName: String) {
        System("System default"),
        Light("Light"),
        Dark("Dark"),
    }

    private val _themeMode = MutableStateFlow(
        dataStore.getString("theme_mode")
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.System
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
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

    fun setSelectedDate(d: LocalDate) {
        _selectedDate.value = d
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
                } catch (e: Exception) {
                    Log.e("CalendarViewModel", "Error parsing ICS file: $uri", e)
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
     * Bulk-inserts the previously parsed [events] into the calendar with id [calendarId].
     * Runs off the main thread; invokes [onDone] on the main thread when complete (or on failure).
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
                    val valuesList = events.map { it.toContentValues(calendarId) }.toTypedArray()
                    app.contentResolver.bulkInsert(CalendarContract.Events.CONTENT_URI, valuesList)
                    _events.value = Event.getAllEvents(app)
                } catch (e: Exception) {
                    Log.e("CalendarViewModel", "Error importing events", e)
                }
            }
            updateWidgets()
            onDone()
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
        }
    }

    /**
     * Loads calendar instances between [start] and [end] off the main thread, filtered to
     * the currently-loaded events whose calendar is visible. The UI consumes this via
     * produceState so no ContentResolver query happens during composition.
     */
    suspend fun visibleInstances(start: Instant, end: Instant): List<Instance> =
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

    fun setCalendarVisibility(calendarId: Long, visible: Boolean) {
        val app = getApplication<Application>()
        // write to the provider's Calendars.VISIBLE field for that calendar
        val values = ContentValues().apply { put(CalendarContract.Calendars.VISIBLE, if (visible) 1 else 0) }
        val uri = CalendarContract.Calendars.CONTENT_URI
        viewModelScope.launch(Dispatchers.IO) {
            try {
                app.contentResolver.update(uri, values, "${CalendarContract.Calendars._ID} = ?", arrayOf(calendarId.toString()))
            } catch (e: Exception) {
                Log.e("CalendarViewModel", "Error setting calendar visibility", e)
            }
            refreshCalendarsAndWidgets()
        }
    }

    fun deleteEventSeries(eventId: Long) {
        upsertEvent(eventId, ContentValues().apply {
            put(CalendarContract.Events.DELETED, 1)
        })
    }

    fun deleteEventInstance(eventId: Long, instanceBeginTime: Long) {
        val event = _events.value.find { it.id == eventId } ?: return
        val instanceDate = Instant.fromEpochMilliseconds(instanceBeginTime)
            .toLocalDateTime(TimeZone.of(event.timezone)).date
        val exdateStr = (event.exdate + instanceDate).distinct().joinToString(",") { it.toIcalBasic() }
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
                    if (newId != null && reminders != null) writeReminders(newId, reminders)
                } else {
                    app.contentResolver.update(uri, values, "${CalendarContract.Events._ID} = ?", arrayOf(eventId.toString()))
                    if (reminders != null) writeReminders(eventId, reminders)
                }
                _events.value = Event.getAllEvents(app)
                updateWidgets()
            } catch (e: Exception) {
                Log.e("CalendarViewModel", "Error upserting event", e)
            }
        }
    }

    /** Replace all reminders for [eventId] with [reminders] (minutes before start). */
    private fun writeReminders(eventId: Long, reminders: List<Int>) {
        val cr = getApplication<Application>().contentResolver
        try {
            cr.delete(
                CalendarContract.Reminders.CONTENT_URI,
                "${CalendarContract.Reminders.EVENT_ID} = ?",
                arrayOf(eventId.toString()),
            )
            reminders.distinct().forEach { minutes ->
                cr.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, minutes)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                })
            }
        } catch (e: Exception) {
            Log.e("CalendarViewModel", "Error writing reminders", e)
        }
    }

    // set the calendar color in the provider and refresh cached calendars
    fun setCalendarColor(calendarId: Long, colorInt: Int) {
        val app = getApplication<Application>()
        val values = ContentValues().apply { put(CalendarContract.Calendars.CALENDAR_COLOR, colorInt) }
        val uri = CalendarContract.Calendars.CONTENT_URI
        viewModelScope.launch(Dispatchers.IO) {
            try {
                app.contentResolver.update(uri, values, "${CalendarContract.Calendars._ID} = ?", arrayOf(calendarId.toString()))
            } catch (e: Exception) {
                Log.e("CalendarViewModel", "Error setting calendar color", e)
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
            Log.e("CalendarViewModel", "Attempted to rename a readonly or non-existent calendar")
            return
        }
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, newDisplayName)
            put(CalendarContract.Calendars.NAME, newDisplayName)
        }
        val uri = CalendarContract.Calendars.CONTENT_URI
        viewModelScope.launch(Dispatchers.IO) {
            try {
                app.contentResolver.update(uri, values, "${CalendarContract.Calendars._ID} = ?", arrayOf(calendarId.toString()))
            } catch (e: Exception) {
                Log.e("CalendarViewModel", "Error renaming calendar", e)
            }
            refreshCalendarsAndWidgets()
        }
    }

    // delete a calendar and refresh caches
    fun deleteCalendar(calendarId: Long) {
        val app = getApplication<Application>()
        val cal = calendars.value.find { it.id == calendarId }
        if (cal == null || !cal.canModify) {
            Log.e("CalendarViewModel", "Attempted to delete a readonly or non-existent calendar")
            return
        }
        val uri = CalendarContract.Calendars.CONTENT_URI
        viewModelScope.launch(Dispatchers.IO) {
            try {
                app.contentResolver.delete(uri, "${CalendarContract.Calendars._ID} = ?", arrayOf(calendarId.toString()))
            } catch (e: Exception) {
                Log.e("CalendarViewModel", "Error deleting calendar", e)
            }
            refreshCalendarsAndWidgets()
        }
    }

    // create a new local/offline calendar in the provider and refresh caches
    fun createLocalCalendar(accountName: String, displayName: String, colorInt: Int, visible: Boolean, accessLevel: Int) {
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

            try {
                app.contentResolver.insert(uri, values)
            } catch (e: Exception) {
                Log.e("CalendarViewModel", "Error creating local calendar", e)
            }
            refreshCalendarsAndWidgets()
        }
    }

}
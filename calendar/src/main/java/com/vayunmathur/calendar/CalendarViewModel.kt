package com.vayunmathur.calendar

import android.app.Application
import android.content.ContentValues
import android.provider.CalendarContract
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.calendar.glance.CalendarGlanceWidget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone


class ContactViewModel(application: Application) : AndroidViewModel(application) {

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events.asStateFlow()

    private val _calendars = MutableStateFlow<List<Calendar>>(emptyList())
    val calendars: StateFlow<List<Calendar>> = _calendars.asStateFlow()

    // map calendarId -> visible (whether to render events from that calendar)
    private val _calendarVisibility = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    val calendarVisibility: StateFlow<Map<Long, Boolean>> = _calendarVisibility.asStateFlow()

    // persist the last viewed date for the calendar (used to restore viewed week across restarts)
    private val _lastViewedDate = MutableStateFlow<LocalDate?>(null)
    val lastViewedDate: StateFlow<LocalDate?> = _lastViewedDate.asStateFlow()

    fun setLastViewedDate(d: LocalDate?) {
        _lastViewedDate.value = d
    }

    init {
        // load events and calendars at startup
        viewModelScope.launch {
            val app = getApplication<Application>()
            _events.value = Event.getAllEvents(app)

            val loaded = Calendar.getAllCalendars(app)
            _calendars.value = loaded

            // initialize visibility from provider's VISIBLE flag
            val visMap = loaded.associate { cal -> cal.id to cal.visible }
            _calendarVisibility.value = visMap
        }
    }

    fun updateWidgets() {
        viewModelScope.launch {
            CalendarGlanceWidget().updateAll(getApplication())
        }
    }

    fun setCalendarVisibility(calendarId: Long, visible: Boolean) {
        val app = getApplication<Application>()
        // write to the provider's Calendars.VISIBLE field for that calendar
        val values = ContentValues().apply { put(CalendarContract.Calendars.VISIBLE, if (visible) 1 else 0) }
        val uri = CalendarContract.Calendars.CONTENT_URI
        app.contentResolver.update(uri, values, "${CalendarContract.Calendars._ID} = ?", arrayOf(calendarId.toString()))

        // refresh cached calendars and visibility map
        val loaded = Calendar.getAllCalendars(app)
        _calendars.value = loaded
        _calendarVisibility.value = loaded.associate { cal -> cal.id to cal.visible }
        updateWidgets()
    }

    fun deleteEvent(eventId: Long) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            upsertEvent(eventId, ContentValues().apply {
                put(CalendarContract.Events.DELETED, 1)
            })
            _events.value = Event.getAllEvents(app)
            updateWidgets()
        }
    }

    // Insert or update event using ContentValues. If eventId is null -> insert, otherwise update.
    fun upsertEvent(eventId: Long?, values: ContentValues): Long? {
        val app = getApplication<Application>()
        val uri = CalendarContract.Events.CONTENT_URI
        return if (eventId == null) {
            val newUri = app.contentResolver.insert(uri, values)
            // refresh events
            _events.value = Event.getAllEvents(app)
            updateWidgets()
            newUri?.lastPathSegment?.toLongOrNull()
        } else {
            app.contentResolver.update(uri, values, "${CalendarContract.Events._ID} = ?", arrayOf(eventId.toString()))
            _events.value = Event.getAllEvents(app)
            updateWidgets()
            eventId
        }
    }

    // set the calendar color in the provider and refresh cached calendars
    fun setCalendarColor(calendarId: Long, colorInt: Int) {
        val app = getApplication<Application>()
        val values = ContentValues().apply { put(CalendarContract.Calendars.CALENDAR_COLOR, colorInt) }
        val uri = CalendarContract.Calendars.CONTENT_URI
        app.contentResolver.update(uri, values, "${CalendarContract.Calendars._ID} = ?", arrayOf(calendarId.toString()))

        // refresh cached calendars and visibility map (color is read from provider)
        val loaded = Calendar.getAllCalendars(app)
        _calendars.value = loaded
        _calendarVisibility.value = loaded.associate { cal -> cal.id to cal.visible }
        updateWidgets()
    }

    // rename a calendar's display name
    fun renameCalendar(calendarId: Long, newDisplayName: String) {
        val app = getApplication<Application>()
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, newDisplayName)
            put(CalendarContract.Calendars.NAME, newDisplayName)
        }
        val uri = CalendarContract.Calendars.CONTENT_URI
        app.contentResolver.update(uri, values, "${CalendarContract.Calendars._ID} = ?", arrayOf(calendarId.toString()))

        val loaded = Calendar.getAllCalendars(app)
        _calendars.value = loaded
        _calendarVisibility.value = loaded.associate { cal -> cal.id to cal.visible }
        updateWidgets()
    }

    // delete a calendar and refresh caches
    fun deleteCalendar(calendarId: Long) {
        val app = getApplication<Application>()
        val uri = CalendarContract.Calendars.CONTENT_URI
        try {
            app.contentResolver.delete(uri, "${CalendarContract.Calendars._ID} = ?", arrayOf(calendarId.toString()))
        } catch (_: Exception) {
            // ignore deletion errors, we'll refresh list anyway
        }

        val loaded = Calendar.getAllCalendars(app)
        _calendars.value = loaded
        _calendarVisibility.value = loaded.associate { cal -> cal.id to cal.visible }
        updateWidgets()
    }

    // create a new local/offline calendar in the provider and refresh caches
    fun createLocalCalendar(accountName: String, displayName: String, colorInt: Int, visible: Boolean, accessLevel: Int) {
        viewModelScope.launch {
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
                val newUri = app.contentResolver.insert(uri, values)
                if (newUri != null) {
                    val loaded = Calendar.getAllCalendars(app)
                    _calendars.value = loaded
                    _calendarVisibility.value = loaded.associate { cal -> cal.id to cal.visible }
                    updateWidgets()
                }
            } catch (_: Exception) {
                // some providers reject inserts; refresh list anyway
                val loaded = Calendar.getAllCalendars(app)
                _calendars.value = loaded
                _calendarVisibility.value = loaded.associate { cal -> cal.id to cal.visible }
                updateWidgets()
            }
        }
    }

}
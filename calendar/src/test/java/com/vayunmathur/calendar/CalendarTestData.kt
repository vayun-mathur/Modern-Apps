package com.vayunmathur.calendar

import android.provider.CalendarContract
import com.vayunmathur.calendar.data.Calendar
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.calendar.util.RRule
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant

/**
 * In-memory CalendarProvider stand-in registered under the real
 * `com.android.calendar` authority, so provider-reading code (Event / Calendar /
 * Instance / CalendarViewModel) sees deterministic rows under Robolectric.
 */
class FakeCalendarProvider : android.content.ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun getType(uri: android.net.Uri): String? = null
    override fun query(
        uri: android.net.Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): android.database.Cursor {
        val cols = projection ?: emptyArray()
        val rows = when (uri.pathSegments.firstOrNull()) {
            "calendars" -> FakeCalendarData.calendars
            "events" -> FakeCalendarData.events
            "reminders" -> FakeCalendarData.reminders
            "instances" -> FakeCalendarData.instances
            else -> emptyList()
        }
        var filtered = rows
        if (selection != null && selectionArgs != null) {
            val args = selectionArgs.filterNotNull()
            filtered = when {
                selection.contains(" LIKE ") -> {
                    val prefix = args.getOrNull(1)?.removeSuffix("%") ?: ""
                    val nameCol = android.provider.CalendarContract.Calendars.NAME
                    rows.filter { (it[nameCol] as? String)?.startsWith(prefix) == true }
                }
                selection.contains(" OR ") && selection.contains(" = ?") -> {
                    val nameCol = android.provider.CalendarContract.Calendars.NAME
                    val names = args.drop(1).toSet()
                    rows.filter { (it[nameCol] as? String) in names }
                }
                selection.contains("_id = ?") -> {
                    val id = args.getOrNull(0)?.toLongOrNull()
                    rows.filter { (it[android.provider.CalendarContract.Calendars._ID] as? Number)?.toLong() == id }
                }
                else -> rows
            }
        }
        val cursor = android.database.MatrixCursor(cols)
        for (row in filtered) cursor.addRow(cols.map { row[it] })
        return cursor
    }

    override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri =
        android.content.ContentUris.withAppendedId(uri, FakeCalendarData.nextId++)

    override fun bulkInsert(uri: android.net.Uri, values: Array<out android.content.ContentValues>): Int = values.size
    override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 1
    override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?): Int = 1
}

/** Mutable rows served by [FakeCalendarProvider]; reset in each test's setup. */
object FakeCalendarData {
    var calendars: List<Map<String, Any?>> = emptyList()
    var events: List<Map<String, Any?>> = emptyList()
    var reminders: List<Map<String, Any?>> = emptyList()
    var instances: List<Map<String, Any?>> = emptyList()
    var nextId: Long = 900

    fun reset() {
        calendars = emptyList(); events = emptyList(); reminders = emptyList(); instances = emptyList(); nextId = 900
    }

    fun register(context: android.content.Context) {
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(
            "com.android.calendar", FakeCalendarProvider(),
        )
    }

    fun calendarRow(cal: Calendar, name: String? = null): Map<String, Any?> = mapOf(
        android.provider.CalendarContract.Calendars._ID to cal.id,
        android.provider.CalendarContract.Calendars.ACCOUNT_NAME to cal.accountName,
        android.provider.CalendarContract.Calendars.CALENDAR_DISPLAY_NAME to cal.displayName,
        android.provider.CalendarContract.Calendars.CALENDAR_COLOR to cal.color,
        android.provider.CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL to cal.accessLevel,
        android.provider.CalendarContract.Calendars.VISIBLE to if (cal.visible) 1 else 0,
        android.provider.CalendarContract.Calendars.NAME to (name ?: cal.displayName),
        android.provider.CalendarContract.Calendars.ACCOUNT_TYPE to android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL,
    )

    fun eventRow(ev: Event, deleted: Int = 0, duration: String? = null, endOverride: Long? = null): Map<String, Any?> = mapOf(
        android.provider.CalendarContract.Events._ID to (ev.id ?: 0L),
        android.provider.CalendarContract.Events.CALENDAR_ID to ev.calendarID,
        android.provider.CalendarContract.Events.TITLE to ev.title,
        android.provider.CalendarContract.Events.DESCRIPTION to ev.description,
        android.provider.CalendarContract.Events.EVENT_LOCATION to ev.location,
        android.provider.CalendarContract.Events.EVENT_COLOR to ev.color,
        android.provider.CalendarContract.Events.DTSTART to ev.start,
        android.provider.CalendarContract.Events.DTEND to (endOverride ?: ev.end),
        android.provider.CalendarContract.Events.ALL_DAY to if (ev.allDay) 1 else 0,
        android.provider.CalendarContract.Events.EVENT_TIMEZONE to ev.timezone,
        android.provider.CalendarContract.Events.DELETED to deleted,
        android.provider.CalendarContract.Events.RRULE to ev.rrule?.asString(ev.startDateTimeDisplay.date, kotlinx.datetime.TimeZone.of(ev.timezone)),
        android.provider.CalendarContract.Events.DURATION to duration,
        android.provider.CalendarContract.Events.EXDATE to ev.exdate.joinToString(",") { it.toString().replace("-", "") },
        android.provider.CalendarContract.Events.RDATE to ev.rdate.joinToString(",") { it.toString().replace("-", "") },
    )

    fun instanceRow(inst: Instance): Map<String, Any?> = mapOf(
        android.provider.CalendarContract.Instances._ID to inst.id,
        android.provider.CalendarContract.Instances.EVENT_ID to inst.eventID,
        android.provider.CalendarContract.Instances.BEGIN to inst.begin,
        android.provider.CalendarContract.Instances.END to inst.end,
        android.provider.CalendarContract.Instances.EVENT_TIMEZONE to inst.timezone,
        android.provider.CalendarContract.Instances.ALL_DAY to if (inst.allDay) 1 else 0,
        android.provider.CalendarContract.Instances.TITLE to inst.eventTitle,
        android.provider.CalendarContract.Instances.DISPLAY_COLOR to inst.color,
        android.provider.CalendarContract.Instances.RRULE to inst.rrule?.asString(inst.startDateTime.date, kotlinx.datetime.TimeZone.of(inst.timezone)),
    )
}

/** Shared factories for calendar tests: a fixed Monday so dates never drift. */
object CalendarTestData {
    val TODAY: LocalDate = LocalDate(2026, 3, 9)
    val ZONE: TimeZone = TimeZone.of("UTC")

    fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        LocalDateTime(date, LocalTime(hour, minute)).toInstant(ZONE).toEpochMilliseconds()

    val PERSONAL = Calendar(
        id = 1, accountName = "Personal", displayName = "PersonalCal",
        color = 0xFF4285F4.toInt(),
        accessLevel = CalendarContract.Calendars.CAL_ACCESS_OWNER, visible = true,
    )
    val WORK = Calendar(
        id = 2, accountName = "Personal", displayName = "WorkCal",
        color = 0xFFEA4335.toInt(),
        accessLevel = CalendarContract.Calendars.CAL_ACCESS_OWNER, visible = true,
    )
    val READONLY = Calendar(
        id = 3, accountName = "Holidays", displayName = "HolidayCal",
        color = 0xFF0B8043.toInt(),
        accessLevel = CalendarContract.Calendars.CAL_ACCESS_READ, visible = false,
    )
    val NO_ACCOUNT = Calendar(
        id = 4, accountName = "", displayName = "LocalCal",
        color = 0xFF607D8B.toInt(),
        accessLevel = CalendarContract.Calendars.CAL_ACCESS_EDITOR, visible = true,
    )

    fun event(
        id: Long? = 10,
        calendar: Calendar = PERSONAL,
        title: String = "Team standup",
        date: LocalDate = TODAY,
        startHour: Int = 9,
        endHour: Int = 10,
        location: String = "",
        description: String = "",
        allDay: Boolean = false,
        rrule: RRule? = null,
        rdate: List<LocalDate> = emptyList(),
        exdate: List<LocalDate> = emptyList(),
        reminders: List<Int> = emptyList(),
        color: Int? = null,
    ): Event {
        val start = if (allDay) at(date, 0) else at(date, startHour)
        val end = if (allDay) at(date.plus(kotlinx.datetime.DatePeriod(days = 1)), 0) else at(date, endHour)
        return Event(
            id = id, calendarID = calendar.id, title = title, description = description,
            location = location, color = color, start = start, end = end,
            timezone = ZONE.id, allDay = allDay, rrule = rrule,
            exdate = exdate, rdate = rdate, reminders = reminders,
        )
    }

    fun instance(event: Event, id: Long = 100 + (event.id ?: 0)): Instance = Instance(
        id = id, eventID = event.id ?: 0, begin = event.start, end = event.end,
        timezone = event.timezone, allDay = event.allDay, eventTitle = event.title,
        color = 0xFF4285F4.toInt(), rrule = event.rrule, exdate = event.exdate,
    )

    fun instance(
        id: Long = 110, eventID: Long = 10, date: LocalDate = TODAY,
        startHour: Int = 9, endHour: Int = 10, title: String = "Team standup",
        allDay: Boolean = false,
    ): Instance = Instance(
        id = id, eventID = eventID,
        begin = if (allDay) at(date, 0) else at(date, startHour),
        end = if (allDay) at(date.plus(kotlinx.datetime.DatePeriod(days = 1)), 0) else at(date, endHour),
        timezone = ZONE.id, allDay = allDay, eventTitle = title,
        color = 0xFF4285F4.toInt(), rrule = null,
    )
}

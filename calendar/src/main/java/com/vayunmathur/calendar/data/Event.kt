package com.vayunmathur.calendar.data
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import com.vayunmathur.library.log.Log
import androidx.core.database.getIntOrNull
import androidx.core.database.getStringOrNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import com.vayunmathur.calendar.util.RRule
import com.vayunmathur.calendar.util.parseIcalBasicDate
import com.vayunmathur.calendar.util.parseIcalOccurrenceDate
import com.vayunmathur.calendar.util.toIcalBasic
import com.vayunmathur.calendar.util.toIcalUtcDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@Serializable
data class Event(
    val id: Long?,
    val calendarID: Long,
    val title: String,
    val description: String,
    val location: String,
    val color: Int?,
    // start and end are utc
    val start: Long,
    val end: Long,
    val timezone: String = "UTC",
    val allDay: Boolean,
    val rrule: RRule?,
    val exdate: List<LocalDate> = emptyList(),
    /**
     * Extra dates this event also happens on, beyond the pattern in [rrule]. RFC 5545 RDATE, which
     * is how "repeat on these specific days" is expressed without a pattern: [rrule] is then null
     * and the occurrences are the start date plus these.
     */
    val rdate: List<LocalDate> = emptyList(),
    val reminders: List<Int> = emptyList(), // minutes before start
) {

    /** RRULE and RDATE are independent ways to repeat, and either one makes this a series. */
    val isRecurring: Boolean get() = rrule != null || rdate.isNotEmpty()

    /** Copy with [date] removed from the extra repeat dates. */
    fun withoutRdate(date: LocalDate): Event = copy(rdate = rdate - date)

    /**
     * RFC 5545 RDATE column value for this event's extra dates, or null when empty so the
     * column is cleared. Timed occurrences use the UTC datetime form: a date alone would be
     * read back as midnight, putting the occurrence at the wrong time of day.
     */
    fun rdateColumnValue(): String? =
        rdate.takeIf { it.isNotEmpty() }?.joinToString(",") { date ->
            if (allDay) date.toIcalBasic()
            else date.toIcalUtcDateTime(startDateTimeDisplay.time, TimeZone.of(timezone))
        }

    /** Zone wall times display in: UTC for all-day, the event zone otherwise. */
    private fun displayZone(): TimeZone =
        if (allDay) TimeZone.UTC else TimeZone.of(timezone)

    val startDateTimeDisplay: LocalDateTime
        get() = Instant.fromEpochMilliseconds(start).toLocalDateTime(displayZone())

    val endDateTimeDisplay: LocalDateTime
        get() = Instant.fromEpochMilliseconds(end).toLocalDateTime(displayZone())

    fun toContentValues(calendarId: Long): ContentValues {
        val tz = if (allDay) "UTC" else timezone
        val tzObj = TimeZone.of(tz)
        // All-day events follow RFC 5545: DTSTART/DTEND are aligned to midnight UTC and
        // DTEND is exclusive (the midnight after the last covered day). `end` is already
        // stored as that exclusive instant, so we only need to snap both ends to midnight.
        val dtstart = if (allDay) startDateTimeDisplay.date.atStartOfDayIn(tzObj).toEpochMilliseconds()
            else startDateTimeDisplay.toInstant(tzObj).toEpochMilliseconds()
        val dtendActual = if (allDay) endDateTimeDisplay.date.atStartOfDayIn(tzObj).toEpochMilliseconds()
            else endDateTimeDisplay.toInstant(tzObj).toEpochMilliseconds()
        return ContentValues().apply {
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.DTSTART, dtstart)
            if (isRecurring) {
                put(CalendarContract.Events.DTEND, null as Long?)
                val duration = (dtendActual - dtstart).milliseconds
                put(CalendarContract.Events.DURATION, duration.toIsoString())
                put(CalendarContract.Events.RRULE, rrule?.asString(startDateTimeDisplay.date, tzObj))
            } else {
                put(CalendarContract.Events.DTEND, dtendActual)
                put(CalendarContract.Events.DURATION, null as String?)
                put(CalendarContract.Events.RRULE, null as String?)
            }
            // Always written, including as null, so clearing the repeat also clears the dates.
            put(CalendarContract.Events.RDATE, rdateColumnValue())
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, tz)
            if (exdate.isNotEmpty()) {
                // A timed occurrence is an instant, so it needs the datetime form: the
                // provider ignores a date-only EXDATE for timed events and the delete
                // silently no-ops. All-day events stay date-only.
                put(
                    CalendarContract.Events.EXDATE,
                    exdate.joinToString(",") { date ->
                        if (allDay) date.toIcalBasic()
                        else date.toIcalUtcDateTime(startDateTimeDisplay.time, tzObj)
                    },
                )
            }
        }
    }

    companion object {
        fun getAllEvents(context: Context): List<Event> {
            val events = mutableListOf<Event>()
            val remindersByEvent = loadReminders(context)

            val uri = CalendarContract.Events.CONTENT_URI
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.CALENDAR_ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.EVENT_COLOR,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.EVENT_TIMEZONE,
                CalendarContract.Events.DELETED,
                CalendarContract.Events.RRULE,
                CalendarContract.Events.DURATION,
                CalendarContract.Events.EXDATE,
                CalendarContract.Events.RDATE,
            )
            try {
                val cursor = context.contentResolver.query(uri, projection, null, null, null)
                cursor?.use { drainEvents(it, remindersByEvent, events) }
            } catch (expected: Exception) {
                Log.error("Event", "Error querying events", expected)
            }

            return events
        }

        /** Drains one query cursor into [events], skipping rows that fail to parse. */
        private fun drainEvents(
            cursor: android.database.Cursor,
            remindersByEvent: Map<Long, List<Int>>,
            events: MutableList<Event>,
        ) {
            val cols = EventRowColumns(cursor)
            while (cursor.moveToNext()) {
                try {
                    readEventRow(cursor, cols, remindersByEvent)?.let { event ->
                        events.add(event)
                    }
                } catch (expected: Exception) {
                    Log.error("Event", "Error constructing event from cursor", expected)
                }
            }
        }

        /** Column indices for one [getAllEvents] query, resolved once per cursor. */
        private class EventRowColumns(c: android.database.Cursor) {
            val id = c.getColumnIndexOrThrow(CalendarContract.Events._ID)
            val cal = c.getColumnIndexOrThrow(CalendarContract.Events.CALENDAR_ID)
            val title = c.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
            val desc = c.getColumnIndexOrThrow(CalendarContract.Events.DESCRIPTION)
            val loc = c.getColumnIndexOrThrow(CalendarContract.Events.EVENT_LOCATION)
            val color = c.getColumnIndexOrThrow(CalendarContract.Events.EVENT_COLOR)
            val start = c.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
            val end = c.getColumnIndexOrThrow(CalendarContract.Events.DTEND)
            val allDay = c.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)
            val tz = c.getColumnIndexOrThrow(CalendarContract.Events.EVENT_TIMEZONE)
            val deleted = c.getColumnIndexOrThrow(CalendarContract.Events.DELETED)
            val rrule = c.getColumnIndexOrThrow(CalendarContract.Events.RRULE)
            val duration = c.getColumnIndexOrThrow(CalendarContract.Events.DURATION)
            val exdate = c.getColumnIndexOrThrow(CalendarContract.Events.EXDATE)
            val rdate = c.getColumnIndexOrThrow(CalendarContract.Events.RDATE)
        }

        /** Reads one event row, or null when the row is deleted or untitled. */
        private fun readEventRow(
            c: android.database.Cursor,
            cols: EventRowColumns,
            remindersByEvent: Map<Long, List<Int>>,
        ): Event? {
            if (c.getInt(cols.deleted) == 1) return null
            val title = c.getString(cols.title) ?: return null
            val id = c.getLong(cols.id)
            val calendarID = c.getLong(cols.cal)
            val description = c.getStringOrNull(cols.desc) ?: ""
            val location = c.getStringOrNull(cols.loc) ?: ""
            val color = c.getIntOrNull(cols.color)
            val start = c.getLong(cols.start)
            val allDay = c.getInt(cols.allDay) == 1
            val tz = runCatching {
                // All-day instants are UTC-midnight wall dates: resolve the
                // stored zone in UTC so a legacy local-zone row cannot shift
                // the parsed RRULE/EXDATE/RDATE days across midnight.
                if (allDay) TimeZone.UTC
                else TimeZone.of(c.getStringOrNull(cols.tz) ?: TimeZone.currentSystemDefault().id)
            }.getOrDefault(TimeZone.currentSystemDefault())
            val end = resolveEndMillis(c, cols, start)
            return Event(
                id,
                calendarID,
                title,
                description,
                location,
                color,
                start,
                end,
                tz.id,
                allDay,
                RRule.parse(c.getStringOrNull(cols.rrule) ?: "", tz),
                parseExdates(c.getStringOrNull(cols.exdate), tz),
                parseRdates(c.getStringOrNull(cols.rdate), tz),
                remindersByEvent[id]?.sorted() ?: emptyList(),
            )
        }

        /** End millis, falling back to start + DURATION when the provider left DTEND at 0. */
        private fun resolveEndMillis(
            c: android.database.Cursor,
            cols: EventRowColumns,
            start: Long,
        ): Long {
            val end = c.getLong(cols.end)
            if (end != 0L) return end
            val durationMillis = c.getStringOrNull(cols.duration)?.let { raw ->
                try {
                    Duration.parse(raw).inWholeMilliseconds
                } catch (_: Exception) {
                    0L
                }
            } ?: return end
            return start + durationMillis
        }

        /**
         * EXDATE values are comma-separated RFC 5545 dates, either YYYYMMDD or a
         * datetime. A datetime is resolved in the event's own zone, because a
         * late-evening occurrence written as UTC lands on the next UTC day and
         * taking the date off the raw string would move it.
         */
        private fun parseExdates(exdateStr: String?, tz: TimeZone): List<LocalDate> {
            return exdateStr?.split(",")?.mapNotNull {
                parseIcalOccurrenceDate(it.trim(), tz)
            } ?: emptyList()
        }

        /** RDATE values, tolerating another client's "TZID=...:"/"VALUE=DATE:" prefix. */
        private fun parseRdates(rdateStr: String?, tz: TimeZone): List<LocalDate> {
            return rdateStr?.split(",")?.mapNotNull {
                parseIcalOccurrenceDate(it.trim().substringAfterLast(':'), tz)
            } ?: emptyList()
        }

        /**
         * Load every reminder in one query and group by event id, avoiding an
         * N+1 lookup per event. Returns event id -> list of minutes-before.
         */
        private fun loadReminders(context: Context): Map<Long, List<Int>> {
            val map = HashMap<Long, MutableList<Int>>()
            runCatching {
                context.contentResolver.query(
                    CalendarContract.Reminders.CONTENT_URI,
                    arrayOf(CalendarContract.Reminders.EVENT_ID, CalendarContract.Reminders.MINUTES),
                    null, null, null,
                )?.use { c ->
                    val eIdx = c.getColumnIndexOrThrow(CalendarContract.Reminders.EVENT_ID)
                    val mIdx = c.getColumnIndexOrThrow(CalendarContract.Reminders.MINUTES)
                    while (c.moveToNext()) {
                        val minutes = c.getInt(mIdx).let { if (it < 0) 0 else it }
                        map.getOrPut(c.getLong(eIdx)) { mutableListOf() }.add(minutes)
                    }
                }
            }.onFailure { Log.error("Event", "Error querying reminders", it) }
            return map
        }
    }
}

package com.vayunmathur.calendar.data
import android.content.Context
import android.provider.CalendarContract
import com.vayunmathur.library.log.Log
import androidx.core.database.getStringOrNull
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import com.vayunmathur.calendar.util.RRule
import kotlinx.serialization.Serializable
import kotlin.time.Instant


@Serializable
data class Instance(
    val id: Long,
    val eventID: Long,
    val begin: Long,
    val end: Long,
    val timezone: String,
    val allDay: Boolean,
    val eventTitle: String,
    val color: Int,
    val rrule: RRule?,
    val exdate: List<LocalDate> = emptyList()
) {

    /**
     * Zone this instance's wall time displays in. All-day times always
     * resolve in UTC: the stored zone of a legacy local-midnight row must
     * not move the wall date across the midnight boundary at render time.
     * Timed instances display in the system zone.
     */
    private fun displayZone(): TimeZone =
        if (allDay) TimeZone.UTC else TimeZone.currentSystemDefault()

    val startDateTimeDisplay: LocalDateTime
        get() = Instant.fromEpochMilliseconds(begin).toLocalDateTime(displayZone())

    val endDateTimeDisplay: LocalDateTime
        get() = Instant.fromEpochMilliseconds(end).toLocalDateTime(displayZone())

    val startDateTime: LocalDateTime
        get() = Instant.fromEpochMilliseconds(begin).toLocalDateTime(displayZone())

    val endDateTime: LocalDateTime
        get() = Instant.fromEpochMilliseconds(end).toLocalDateTime(displayZone())


    val spanDays: List<LocalDate>
        get() {
            val startDate = startDateTime.date
            val endDate = if (endDateTime.time == LocalTime(0, 0)) {
                endDateTime.date - DatePeriod(days = 1)
            } else {
                endDateTime.date
            }
            // A zero-duration event at midnight (begin == end) would compute an
            // endDate before its startDate and span no days at all, making it
            // invisible in every view (and crashing EventPositioner, which reads
            // spanDays.first()). It still happens on its start day.
            if (endDate < startDate) return listOf(startDate)
            return (startDate..endDate).toList()
        }


    companion object {
        /**
         * Best-match [Instance] for a reminder tap: exact begin match first, then
         * any instance of the event in a tight window around it (covers a
         * recurrence shift or clock skew). Null when the provider is unreadable
         * (locked boot, missing permission) or the event is gone.
         */
        fun findForReminder(context: Context, eventId: Long, begin: Long, end: Long): Instance? {
            return runCatching {
                val start = Instant.fromEpochMilliseconds((begin - 60_000L).coerceAtLeast(0L))
                val finish = Instant.fromEpochMilliseconds(
                    if (end > begin) end + 60_000L else begin + 3_600_000L,
                )
                val window = getInstances(context, start, finish)
                window.firstOrNull { it.eventID == eventId && it.begin == begin }
                    ?: window.firstOrNull { it.eventID == eventId }
            }.getOrNull()
        }
        /** Zone a timed instance's wall time displays in (all-day uses UTC; see above). */
        private fun companionDisplayZone(allDay: Boolean): TimeZone =
            if (allDay) TimeZone.UTC else TimeZone.currentSystemDefault()
        /**
         * Instances between [startTime] and [endTime] whose event still exists and
         * whose calendar is visible. [getInstances] reads the provider directly,
         * which can still return rows for just-deleted events (sync-adapter delete
         * semantics / sync lag) with no visibility info — the widget must apply
         * the same live-event + visibility filtering as the in-app views or stale
         * events linger until their date passes.
         */
        fun getVisibleInstances(context: Context, startTime: Instant, endTime: Instant): List<Instance> {
            val instances = getInstances(context, startTime, endTime)
            if (instances.isEmpty()) return emptyList()
            val eventsById = Event.getAllEvents(context).associateBy { it.id }
            val visibility = Calendar.getAllCalendars(context).associate { it.id to it.visible }
            return instances.filter { instance ->
                val event = eventsById[instance.eventID] ?: return@filter false
                visibility[event.calendarID] ?: true
            }
        }

        fun getInstances(context: Context, startTime: Instant, endTime: Instant): List<Instance> {
            val instances = mutableListOf<Instance>()

            val projection = arrayOf(
                CalendarContract.Instances._ID,
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_TIMEZONE,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.DISPLAY_COLOR,
                CalendarContract.Instances.RRULE
            )
            try {
                val cursor = CalendarContract.Instances.query(
                    context.contentResolver,
                    projection,
                    startTime.toEpochMilliseconds(),
                    endTime.toEpochMilliseconds()
                )
                cursor?.use {
                    val idIdx = it.getColumnIndexOrThrow(CalendarContract.Instances._ID)
                    val eventIdIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
                    val beginIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                    val endIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.END)
                    val tzIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_TIMEZONE)
                    val allDayIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
                    val titleIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                    val colorIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.DISPLAY_COLOR)
                    val rruleIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.RRULE)

                    while (it.moveToNext()) {
                        try {
                            val id = it.getLong(idIdx)
                            val eventID = it.getLong(eventIdIdx)
                            val start = it.getLong(beginIdx)
                            val end = it.getLong(endIdx)
                            val tz = runCatching {
                                // All-day instants are UTC-midnight wall dates: resolve
                                // the stored zone in UTC so a legacy local-zone row
                                // cannot shift the parsed RRULE/day across midnight.
                                if (it.getInt(allDayIdx) > 0) TimeZone.UTC
                                else TimeZone.of(
                                    it.getStringOrNull(tzIdx) ?: TimeZone.currentSystemDefault().id
                                )
                            }.getOrDefault(TimeZone.currentSystemDefault())
                            val timezone = tz.id
                            val allDay = it.getInt(allDayIdx) > 0
                            val eventTitle = it.getString(titleIdx)
                            val color = it.getInt(colorIdx)
                            val rrule = RRule.parse(it.getStringOrNull(rruleIdx) ?: "", tz)

                            instances.add(Instance(id, eventID, start, end, timezone, allDay, eventTitle, color, rrule))
                        } catch (expected: Exception) {
                            Log.error("Instance", "Error constructing instance from cursor", expected)
                        }
                    }
                }
            } catch (expected: Exception) {
                Log.error("Instance", "Error querying instances", expected)
            }

            return instances
        }
    }
}

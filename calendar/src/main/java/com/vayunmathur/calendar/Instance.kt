package com.vayunmathur.calendar

import android.content.Context
import android.provider.CalendarContract
import androidx.core.database.getStringOrNull
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
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
    val rrule: RRule?
) {

    val startDateTimeDisplay: LocalDateTime
        get() = Instant.fromEpochMilliseconds(begin).toLocalDateTime(TimeZone.of(timezone))

    val endDateTimeDisplay: LocalDateTime
        get() = Instant.fromEpochMilliseconds(end).toLocalDateTime(TimeZone.of(timezone))

    val startDateTime: LocalDateTime
        get() = Instant.fromEpochMilliseconds(begin).toLocalDateTime(if(allDay) TimeZone.UTC else TimeZone.currentSystemDefault())

    val endDateTime: LocalDateTime
        get() = Instant.fromEpochMilliseconds(end).toLocalDateTime(if(allDay) TimeZone.UTC else TimeZone.currentSystemDefault())


    val spanDays: List<LocalDate>
        get() {
            val startDate = startDateTime.date
            val endDate = if (endDateTime.time == LocalTime(
                    0,
                    0,
                    0
                )
            ) (endDateTime.date - DatePeriod(days = 1)) else endDateTime.date
            return (startDate..endDate).toList()
        }


    companion object {
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
            val cursor = CalendarContract.Instances.query(
                context.contentResolver,
                projection,
                startTime.toEpochMilliseconds(),
                endTime.toEpochMilliseconds()
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(CalendarContract.Instances._ID))
                    val eventID =
                        it.getLong(it.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID))
                    val start =
                        it.getLong(it.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN))
                    val end = it.getLong(it.getColumnIndexOrThrow(CalendarContract.Instances.END))
                    val timezone =
                        it.getString(it.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_TIMEZONE))
                    val allDay =
                        it.getInt(it.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)) > 0
                    val eventTitle = it.getString(it.getColumnIndexOrThrow(CalendarContract.Instances.TITLE))
                    val color = it.getInt(it.getColumnIndexOrThrow(CalendarContract.Instances.DISPLAY_COLOR))
                    val rrule = RRule.parse(it.getStringOrNull(it.getColumnIndexOrThrow(CalendarContract.Instances.RRULE)) ?: "",
                        TimeZone.of(timezone))

                    //if (end < start) continue
                    instances.add(Instance(id, eventID, start, end, timezone, allDay, eventTitle, color, rrule))
                }
            }

            return instances
        }
    }
}

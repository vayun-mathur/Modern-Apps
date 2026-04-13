package com.vayunmathur.calendar.intents

import android.content.ContentValues
import android.provider.CalendarContract
import com.vayunmathur.calendar.data.Calendar
import com.vayunmathur.library.intents.calendar.EventData
import com.vayunmathur.library.util.AssistantIntent
import kotlinx.datetime.TimeZone
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer

@OptIn(InternalSerializationApi::class)
class InsertIntent: AssistantIntent<EventData, Unit>(serializer<EventData>(), serializer<Unit>()) {

    override suspend fun performCalculation(input: EventData) {
        val calendarId = Calendar.getAllCalendars(this).firstOrNull()?.id ?: 1L
        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, input.start)
            put(CalendarContract.Events.DTEND, input.end)
            put(CalendarContract.Events.TITLE, input.title)
            put(CalendarContract.Events.EVENT_LOCATION, input.location)
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.currentSystemDefault().id)
        }
        contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
    }
}

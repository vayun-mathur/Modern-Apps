package com.vayunmathur.calendar.intents

import android.content.ContentValues
import android.provider.CalendarContract
import androidx.glance.appwidget.updateAll
import com.vayunmathur.calendar.data.Calendar
import com.vayunmathur.calendar.widget.glance.CalendarGlanceWidget
import com.vayunmathur.calendar.widget.glance.CalendarMonthGlanceWidget
import com.vayunmathur.library.intents.calendar.EventData
import com.vayunmathur.library.util.AssistantIntent
import kotlinx.datetime.TimeZone
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer

@OptIn(InternalSerializationApi::class)
/**
 * Assistant write intent: validates event data, inserts it into the first visible
 * writable calendar, and refreshes the widgets.
 */
class InsertIntent: AssistantIntent<EventData, Unit>(serializer<EventData>(), serializer<Unit>()) {

    override suspend fun performCalculation(input: EventData) {
        // Exported endpoint: invalid input is rejected (caller gets RESULT_CANCELED)
        // instead of writing a corrupt row to the provider.
        require(input.title.isNotBlank()) { "Event title must not be empty" }
        require(input.end >= input.start) { "Event end must not be before start" }
        // No synthetic fallback id: inserting into a non-existent calendar either
        // fails or lands in the wrong account. Only a visible, writable calendar
        // is acceptable; otherwise the call is rejected.
        val calendar = Calendar.getAllCalendars(this).firstOrNull { it.visible && it.canModify }
            ?: throw IllegalArgumentException("No writable, visible calendar available")
        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, input.start)
            put(CalendarContract.Events.DTEND, input.end)
            put(CalendarContract.Events.TITLE, input.title)
            put(CalendarContract.Events.EVENT_LOCATION, input.location)
            put(CalendarContract.Events.CALENDAR_ID, calendar.id)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.currentSystemDefault().id)
        }
        contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: throw IllegalStateException("Calendar provider rejected the insert")
        runCatching { CalendarGlanceWidget().updateAll(this) }
        runCatching { CalendarMonthGlanceWidget().updateAll(this) }
    }
}

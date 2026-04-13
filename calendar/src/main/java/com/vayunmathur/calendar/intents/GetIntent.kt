package com.vayunmathur.calendar.intents

import com.vayunmathur.calendar.data.Event
import com.vayunmathur.library.intents.calendar.EventData
import com.vayunmathur.library.util.AssistantIntent
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer

@OptIn(InternalSerializationApi::class)
class GetIntent: AssistantIntent<Unit, List<EventData>>(serializer<Unit>(), serializer<List<EventData>>()) {

    override suspend fun performCalculation(input: Unit): List<EventData> {
        return Event.getAllEvents(this).map { event ->
            EventData(
                title = event.title,
                start = event.start,
                end = event.end,
                location = event.location
            )
        }
    }
}

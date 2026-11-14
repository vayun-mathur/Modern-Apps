package com.vayunmathur.calendar.intents

import com.vayunmathur.calendar.data.Event
import com.vayunmathur.library.intents.calendar.EventData
import com.vayunmathur.library.util.AssistantIntent
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer

@OptIn(InternalSerializationApi::class)
/** Assistant read intent: returns the most recently starting events, capped for binder transport. */
class GetIntent: AssistantIntent<Unit, List<EventData>>(serializer<Unit>(), serializer<List<EventData>>()) {

    override suspend fun performCalculation(input: Unit): List<EventData> {
        // The result crosses a binder as JSON: an unbounded event history can
        // exceed the transaction buffer (TransactionTooLarge). Cap the payload to
        // the most recently starting events; each entry is small, so this stays
        // well under the limit while keeping the freshest data.
        return Event.getAllEvents(this)
            .sortedByDescending { it.start }
            .take(MAX_RESULTS)
            .map { event ->
                EventData(
                    title = event.title,
                    start = event.start,
                    end = event.end,
                    location = event.location
                )
            }
    }

    private companion object {
        const val MAX_RESULTS = 500
    }
}

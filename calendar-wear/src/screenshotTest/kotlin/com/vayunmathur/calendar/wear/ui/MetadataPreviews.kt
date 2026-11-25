package com.vayunmathur.calendar.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material.MaterialTheme
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.calendar.wear.domain.WearCalendarEvent
import com.vayunmathur.calendar.wear.domain.WearCalendarSnapshot

/** Round watch, 454x454px class (227dp at 320dpi). */
private const val ROUND = "spec:width=227dp,height=227dp,dpi=320"

/** Fixed preview instant: 2026-11-25 14:41 UTC. */
private const val FIXED_NOW_MILLIS = 1795617660000L

private val fixtureEvents = listOf(
    WearCalendarEvent(eventId = 1, title = "Design review", location = "Room 4", description = "Bring the latest mockups.", calendarName = "Work", startMillis = FIXED_NOW_MILLIS + 3_600_000L, endMillis = FIXED_NOW_MILLIS + 7_200_000L),
    WearCalendarEvent(eventId = 2, title = "Dentist", location = "High Street", calendarName = "Personal", startMillis = FIXED_NOW_MILLIS + 86_400_000L, endMillis = FIXED_NOW_MILLIS + 90_000_000L),
    WearCalendarEvent(eventId = 3, title = "Public holiday", calendarName = "Holidays", startMillis = FIXED_NOW_MILLIS + 172_800_000L, endMillis = FIXED_NOW_MILLIS + 259_200_000L, allDay = true),
)

/**
 * Store listing images for `:calendar:wear` — every top-level screen (agenda, event detail).
 * `./gradlew :calendar:wear:metadata` copies renders into `metadata_data/photos/calendar-wear/wear/`.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-agenda-round", device = ROUND, group = "wear")
    @Composable
    fun Preview1AgendaRound() {
        RoundPreview {
            WearAgendaContent(
                snapshot = WearCalendarSnapshot(generatedAtMillis = FIXED_NOW_MILLIS, events = fixtureEvents),
                nowMillis = FIXED_NOW_MILLIS,
                onOpenEvent = {},
                onRequestSync = { true },
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-event-round", device = ROUND, group = "wear")
    @Composable
    fun Preview2EventRound() {
        RoundPreview { WearEventContent(event = fixtureEvents[0], onBack = {}) }
    }
}

/** Round-watch framing: clip Layoutlib's rectangular canvas to the watch shape. */
@Composable
private fun RoundPreview(content: @Composable () -> Unit) {
    MaterialTheme {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colors.background)) { content() }
    }
}

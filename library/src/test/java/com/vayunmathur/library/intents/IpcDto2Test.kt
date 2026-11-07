package com.vayunmathur.library.intents

import com.vayunmathur.library.intents.calendar.EventData
import com.vayunmathur.library.intents.clock.SetAlarmData
import com.vayunmathur.library.intents.clock.SetTimerData
import com.vayunmathur.library.intents.contacts.ContactData
import com.vayunmathur.library.intents.email.EmailData
import com.vayunmathur.library.intents.email.EmailSearchQuery
import com.vayunmathur.library.intents.findfamily.FamilyMemberData
import com.vayunmathur.library.intents.maps.DirectionsLatLng
import com.vayunmathur.library.intents.maps.DirectionsRequest
import com.vayunmathur.library.intents.maps.DirectionsResult
import com.vayunmathur.library.intents.maps.DirectionsSegment
import com.vayunmathur.library.intents.music.MusicSearchResult
import com.vayunmathur.library.intents.music.PlayMusicData
import com.vayunmathur.library.intents.notes.InsertNoteData
import com.vayunmathur.library.intents.notes.NoteData
import com.vayunmathur.library.intents.todo.CompleteTodoData
import com.vayunmathur.library.intents.todo.GetTodosQuery
import com.vayunmathur.library.intents.todo.InsertTodoData
import com.vayunmathur.library.intents.todo.TodoData
import com.vayunmathur.library.intents.weather.WeatherData
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every remaining IPC DTO round-trips through JSON with defaults intact. */
class IpcDto2Test {
    private val json = Json

    private inline fun <reified T : Any> roundTrip(value: T, serializer: kotlinx.serialization.KSerializer<T>) {
        assertEquals(value, json.decodeFromString(serializer, json.encodeToString(serializer, value)))
    }

    @Test fun simpleDtos() {
        roundTrip(EventData("Meet", 100, 200, "Office"), EventData.serializer())
        roundTrip(EventData("Meet", 100, 200), EventData.serializer())
        assertEquals("", EventData("Meet", 100, 200).location)
        roundTrip(SetAlarmData(7, 30, "Wake"), SetAlarmData.serializer())
        roundTrip(SetAlarmData(7, 30), SetAlarmData.serializer())
        roundTrip(SetTimerData(600, "Tea"), SetTimerData.serializer())
        roundTrip(ContactData("Ada", "+1-555"), ContactData.serializer())
        roundTrip(FamilyMemberData("Bo", "Park", 1.5, 2.5), FamilyMemberData.serializer())
        roundTrip(NoteData("t", "c"), NoteData.serializer())
        roundTrip(InsertNoteData("t", "c"), InsertNoteData.serializer())
        roundTrip(MusicSearchResult(9, "Song", "track"), MusicSearchResult.serializer())
        roundTrip(PlayMusicData(9, "album"), PlayMusicData.serializer())
        roundTrip(EmailSearchQuery("invoice"), EmailSearchQuery.serializer())
    }

    @Test fun emailDtoNullables() {
        val full = EmailData("Subj", "a@x.io", "b@x.io", "2026-01-01", "Body", isRead = true)
        roundTrip(full, EmailData.serializer())
        val sparse = EmailData("Subj", "a@x.io", date = "2026-01-01", isRead = false)
        roundTrip(sparse, EmailData.serializer())
        assertNull(sparse.to); assertNull(sparse.body)
    }

    @Test fun directionsDtos() {
        val result = DirectionsResult(
            polyline = listOf(DirectionsLatLng(1.0, 2.0), DirectionsLatLng(3.0, 4.0)),
            segments = listOf(DirectionsSegment(listOf(DirectionsLatLng(1.0, 2.0)), 0.4)),
            distanceMeters = 1200.0, durationSeconds = 300.0,
        )
        roundTrip(result, DirectionsResult.serializer())
        roundTrip(DirectionsRequest(1.0, 2.0, 3.0, 4.0, "walk"), DirectionsRequest.serializer())
        roundTrip(DirectionsResult(emptyList(), emptyList(), 0.0, 0.0), DirectionsResult.serializer())
    }

    @Test fun todoDtosAndDefaults() {
        val todo = TodoData(id = "sync-1", title = "Call mom", dueAt = 123L, done = true, priority = 1, list = "family")
        roundTrip(todo, TodoData.serializer())
        val minimal = TodoData(id = "sync-2", title = "Plain")
        roundTrip(minimal, TodoData.serializer())
        assertEquals("", minimal.notes); assertNull(minimal.dueAt); assertEquals(0, minimal.priority)
        roundTrip(GetTodosQuery(), GetTodosQuery.serializer())
        assertEquals("today", GetTodosQuery().scope)
        roundTrip(InsertTodoData("New", dueAt = 5L, priority = 2), InsertTodoData.serializer())
        roundTrip(CompleteTodoData(id = "sync-1"), CompleteTodoData.serializer())
        roundTrip(CompleteTodoData(title = "By title"), CompleteTodoData.serializer())
    }

    @Test fun weatherDtoErrorAndSunTimes() {
        val ok = WeatherData(
            locationName = "Oslo", temperatureCelsius = 3.0, feelsLikeCelsius = 1.0,
            condition = "Snow", highCelsius = 5.0, lowCelsius = -2.0,
            precipitationChancePercent = 80, humidityPercent = 70, windKph = 20.0,
            windDirection = "NW", uvIndex = 1.0, sunriseEpochSec = 1000, sunsetEpochSec = 2000,
        )
        roundTrip(ok, WeatherData.serializer())
        assertNull(ok.error)
        val failed = WeatherData(
            locationName = null, temperatureCelsius = 0.0, feelsLikeCelsius = 0.0,
            condition = "", highCelsius = 0.0, lowCelsius = 0.0, precipitationChancePercent = 0,
            humidityPercent = 0, windKph = 0.0, windDirection = "", uvIndex = 0.0,
            error = "city not found",
        )
        roundTrip(failed, WeatherData.serializer())
        assertTrue(failed.error!!.contains("not found"))
        // toString is what the assistant caller feeds the LLM: it must carry the values.
        assertTrue(ok.toString().contains("Oslo"))
    }
}

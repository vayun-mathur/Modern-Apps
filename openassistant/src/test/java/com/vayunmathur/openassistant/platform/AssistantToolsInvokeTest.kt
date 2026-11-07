package com.vayunmathur.openassistant.platform

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.openassistant.data.AppDatabase
import com.vayunmathur.openassistant.domain.ParsedToolCall
import com.vayunmathur.openassistant.util.InferenceService
import com.vayunmathur.openassistant.util.ShadowUnresolvableIntent
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AssistantTools.invoke dispatch: unknown/invalid calls, missing-app results
 * for every cross-app tool, device-local tools, and memory tools.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(shadows = [ShadowUnresolvableIntent::class])
class AssistantToolsInvokeTest {

    private lateinit var db: AppDatabase
    private lateinit var tools: AssistantTools

    @BeforeTest fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val activity = org.robolectric.Robolectric.buildActivity(
            androidx.activity.ComponentActivity::class.java).setup().get()
        com.vayunmathur.openassistant.MainActivity.intentLauncher =
            com.vayunmathur.library.util.IntentLauncher(activity)
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        tools = AssistantTools(ctx, db.memoryDao())
    }

    @AfterTest fun tearDown() { db.close() }

    private fun call(name: String, vararg args: Pair<String, String>) =
        tools.invoke(ParsedToolCall(name, mapOf(*args)))

    @Test fun unknownAndInvalidCalls() {
        assertEquals("Error: unknown tool 'nope'", call("nope"))
        assertEquals("Error: create_note needs title", call("create_note", "content" to "x"))
        assertTrue(
            call("set_timer", "seconds" to "abc").contains("must be a number"))
        assertEquals(28, tools.declarations.size)
    }

    @Test fun crossAppToolsReportMissingApps() {
        assertTrue(call("get_notes").startsWith("The Notes app is required"))
        assertTrue(call("create_note", "title" to "T", "content" to "C").startsWith("The Notes app"))
        assertTrue(call("get_todos").startsWith("The Todo app is required"))
        assertTrue(call("create_todo", "title" to "T").startsWith("The Todo app"))
        assertTrue(call("complete_todo", "id" to "1").startsWith("The Todo app"))
        assertTrue(call("get_contacts").startsWith("The Contacts app is required"))
        assertTrue(call("create_contact", "name" to "A", "phone_number" to "1").startsWith("The Contacts app"))
        assertTrue(call("get_calendar_events").startsWith("The Calendar app is required"))
        assertTrue(
            call("create_calendar_event", "title" to "M", "start" to "1", "end" to "2")
                .startsWith("The Calendar app"))
        assertTrue(call("get_family_locations").startsWith("The FindFamily app is required"))
        assertTrue(
            call("create_location_share_link", "name" to "A", "duration_millis" to "60000")
                .startsWith("The FindFamily app"))
        assertTrue(call("search_music", "query" to "jazz").startsWith("The Music app is required"))
        assertTrue(call("play_music", "id" to "3", "type" to "song").startsWith("The Music app"))
        assertTrue(call("search_emails", "query" to "hi").startsWith("The Email app is required"))
        assertTrue(call("get_recent_emails").startsWith("The Email app"))
        assertTrue(call("set_alarm", "hour" to "7", "minute" to "30").startsWith("The Clock app is required"))
        assertTrue(call("set_timer", "seconds" to "60").startsWith("The Clock app"))
        assertTrue(
            call("get_weather", "latitude" to "1.5", "longitude" to "2.5")
                .startsWith("The Weather app is required"))
        assertTrue(call("get_weather_by_name", "location" to "Lisbon").startsWith("The Weather app"))
    }

    @Test fun deviceToolsRunLocally() {
        val dateTime = call("get_local_current_date_time")
        assertTrue(dateTime.contains("epochMillis="), dateTime)
        // App list renders whatever Robolectric reports installed (possibly none).
        assertTrue(call("get_app_list").startsWith("["))
        // Unknown package has no launch intent: missing-app handler answers.
        assertTrue(
            call("open_app", "package_id" to "com.example.absent")
                .startsWith("The com.example.absent app is required"))
        assertEquals("Opened messaging app.", call("send_message", "recipient" to "123", "message" to "hi"))
        assertEquals("Opened dialer.", call("make_phone_call", "recipient" to "123"))
        assertEquals(
            "Conversation title set successfully",
            call("set_conversation_title", "new_title" to "Trip planning"))
        assertEquals("Trip planning", InferenceService.newTitle)
    }

    @Test fun memoryToolsRoundTrip() {
        assertEquals("Success: Added memory", call("add_to_memory", "content" to "likes tea"))
        val listed = call("get_memories")
        assertTrue(listed.contains("likes tea"), listed)
        assertEquals("Success: Added memory", call("add_to_memory", "content" to "second"))
        assertTrue(call("get_memories").contains("second"))
    }

    @Test fun memoryToolsWithoutDaoError() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val noDao = AssistantTools(ctx, null)
        assertEquals("Error: MemoryDao is null", noDao.invoke(ParsedToolCall("get_memories", emptyMap())))
        assertEquals(
            "Error: MemoryDao is null",
            noDao.invoke(ParsedToolCall("add_to_memory", mapOf("content" to "x"))))
        assertEquals(
            "Error: MemoryDao is null",
            noDao.invoke(ParsedToolCall("remove_memory", mapOf("id" to "1"))))
    }
}

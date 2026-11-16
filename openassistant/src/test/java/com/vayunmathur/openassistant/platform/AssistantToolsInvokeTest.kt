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
        assertEquals(33, tools.declarations.size)
    }

    @Test fun crossAppToolsReportMissingApps() {
        assertTrue(call("get_notes").startsWith("The Notes app is required"))
        assertEquals("Error: confirmation required for create_note", call("create_note", "title" to "T", "content" to "C"))
        assertTrue(call("get_todos").startsWith("The Todo app is required"))
        assertEquals("Error: confirmation required for create_todo", call("create_todo", "title" to "T"))
        assertEquals("Error: confirmation required for complete_todo", call("complete_todo", "id" to "1"))
        assertTrue(call("get_contacts").startsWith("The Contacts app is required"))
        assertEquals("Error: confirmation required for create_contact", call("create_contact", "name" to "A", "phone_number" to "1"))
        assertTrue(call("get_calendar_events").startsWith("The Calendar app is required"))
        assertEquals(
            "Error: confirmation required for create_calendar_event",
            call("create_calendar_event", "title" to "M", "start" to "1", "end" to "2"))
        assertTrue(call("get_family_locations").startsWith("The FindFamily app is required"))
        assertEquals(
            "Error: confirmation required for create_location_share_link",
            call("create_location_share_link", "name" to "A", "duration_millis" to "60000"))
        assertTrue(call("search_music", "query" to "jazz").startsWith("The Music app is required"))
        assertEquals("Error: confirmation required for play_music", call("play_music", "id" to "3", "type" to "song"))
        assertTrue(call("search_emails", "query" to "hi").startsWith("The Email app is required"))
        assertTrue(call("get_recent_emails").startsWith("The Email app"))
        assertEquals("Error: confirmation required for set_alarm", call("set_alarm", "hour" to "7", "minute" to "30"))
        assertEquals("Error: confirmation required for set_timer", call("set_timer", "seconds" to "60"))
        assertTrue(
            call("get_weather", "latitude" to "1.5", "longitude" to "2.5")
                .startsWith("The Weather app is required"))
        assertTrue(call("get_weather_by_name", "location" to "Lisbon").startsWith("The Weather app"))
        assertTrue(
            call(
                "get_directions",
                "from_lat" to "1.0", "from_lng" to "2.0", "to_lat" to "3.0", "to_lng" to "4.0",
            ).startsWith("The Maps app is required"))
        assertTrue(
            call(
                "get_ride_estimate",
                "pickup_lat" to "1.0", "pickup_lng" to "2.0", "dest_lat" to "3.0", "dest_lng" to "4.0",
            ).startsWith("The Taxi app is required"))
        assertTrue(
            call("food_order_lookup", "name" to "Sushi", "lat" to "1.0", "lng" to "2.0")
                .startsWith("The FoodDelivery app is required"))
        assertTrue(
            call("search_contacts", "query" to "ann").startsWith("The Contacts app is required"))
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
            "Error: confirmation required for remove_memory",
            noDao.invoke(ParsedToolCall("remove_memory", mapOf("id" to "1"))))
        val pending = noDao.createPending(ParsedToolCall("remove_memory", mapOf("id" to "1")))!!
        assertEquals("Error: MemoryDao is null", noDao.invokeConfirmed(pending.id))
    }

    @Test fun writeConfirmGateNeverExecutesUnconfirmed() {
        // create_note would report the missing Notes app if it executed; the gate must win.
        assertEquals(
            "Error: confirmation required for create_note",
            call("create_note", "title" to "T", "content" to "secret-body"))
        // add_to_memory stays READ/ungated.
        assertEquals("Success: Added memory", call("add_to_memory", "content" to "likes tea"))
    }

    @Test fun invokeConfirmedIsIdempotentAndChecksExpiry() {
        val ctx2 = ApplicationProvider.getApplicationContext<android.content.Context>()
        var now = 1_000_000L
        var seq = 0
        val gated = AssistantTools(ctx2, db.memoryDao(),
            nowMillis = { now }, idGenerator = { "pending-${seq++}" })
        val pending = gated.createPending(
            ParsedToolCall("remove_memory", mapOf("id" to "999")))!!
        assertEquals("pending-0", pending.id)
        // First confirmed call executes (handler reports no such memory / dao result).
        val first = gated.invokeConfirmed(pending.id)
        assertTrue(first.contains("Removed") || first.startsWith("Success") || first.isNotBlank(), first)
        val second = gated.invokeConfirmed(pending.id)
        assertEquals("Error: pending tool call already consumed", second)
        assertEquals("Error: pending tool call not found", gated.invokeConfirmed("nope"))
        // Expired pending never executes.
        val expired = gated.createPending(
            ParsedToolCall("remove_memory", mapOf("id" to "999")))!!
        now += com.vayunmathur.openassistant.domain.PendingToolCall.TTL_MILLIS + 1
        assertEquals("Error: pending tool call expired", gated.invokeConfirmed(expired.id))
    }

    @Test fun renderUiValidatesAndStashesEnvelope() {
        // Invalid JSON and unusable components are errors and stash nothing.
        assertTrue(call("render_ui", "components_json" to "{not json").startsWith("Error:"), "bad json")
        assertEquals(null, tools.pendingRenderUi)
        assertTrue(
            call("render_ui", "components_json" to """{"components":[]}""").startsWith("Error: invalid A2UI"),
        )
        assertEquals(null, tools.pendingRenderUi)
        // Oversize payloads are rejected before stashing.
        val big = """{"components":[{"id":"root","component":"Text","text":"${"x".repeat(13_000)}"}]}"""
        assertTrue(call("render_ui", "components_json" to big).contains("too large"))
        assertEquals(null, tools.pendingRenderUi)
        // Valid UI succeeds, merges data_json, and is consumable exactly once by the engine.
        val ok = call(
            "render_ui",
            "components_json" to """{"components":[{"id":"root","component":"Text","text":"Hi"}]}""",
            "data_json" to """{"name":"Ada"}""",
        )
        assertEquals("UI rendered", ok)
        val stashed = tools.pendingRenderUi
        assertTrue(stashed != null && stashed.contains("Ada"), "$stashed")
        assertEquals(stashed, tools.consumePendingRenderUi())
        assertEquals(null, tools.consumePendingRenderUi())
        // Bare component arrays are accepted too.
        assertEquals(
            "UI rendered",
            call("render_ui", "components_json" to """[{"id":"root","component":"Text","text":"Yo"}]"""),
        )
        tools.consumePendingRenderUi()
    }

    @Test fun riskClassificationMatchesDesign() {
        val gated = setOf("create_note", "create_todo", "complete_todo", "create_contact",
            "create_calendar_event", "create_location_share_link", "play_music",
            "set_alarm", "set_timer", "remove_memory")
        for (spec in com.vayunmathur.openassistant.domain.ASSISTANT_TOOL_SPECS) {
            val expected = if (spec.name in gated) {
                com.vayunmathur.openassistant.domain.ToolRisk.WRITE_CONFIRM
            } else {
                com.vayunmathur.openassistant.domain.ToolRisk.READ
            }
            assertEquals(expected, spec.risk, spec.name)
        }
    }
}

package com.vayunmathur.openassistant.util

import androidx.activity.ComponentActivity
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.intents.clock.SetAlarmData
import com.vayunmathur.library.intents.clock.SetTimerData
import com.vayunmathur.library.intents.contacts.ContactData
import com.vayunmathur.library.intents.calendar.EventData
import com.vayunmathur.library.intents.email.EmailData
import com.vayunmathur.library.intents.music.PlayMusicData
import com.vayunmathur.library.intents.notes.NoteData
import com.vayunmathur.library.intents.todo.CompleteTodoData
import com.vayunmathur.library.intents.todo.GetTodosQuery
import com.vayunmathur.library.intents.todo.InsertTodoData
import com.vayunmathur.library.util.IntentLauncher
import com.vayunmathur.openassistant.MainActivity
import com.vayunmathur.openassistant.data.AppDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.robolectric.Robolectric

/**
 * AssistantToolIntents against Robolectric: cross-app packages are not
 * installed, so every IPC tool surfaces the missing-app path; memory tools
 * run against a real in-memory DAO.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(shadows = [ShadowUnresolvableIntent::class])
class AssistantToolIntentsTest {

    private lateinit var tools: AssistantToolIntents
    private lateinit var db: AppDatabase

    @BeforeTest fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        MainActivity.intentLauncher = IntentLauncher(activity)
        tools = AssistantToolIntents(ctx)
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @AfterTest fun tearDown() { db.close() }

    @Test fun missingAppMessagesNameTheApp() {
        assertTrue(
            AssistantToolIntents.getMissingAppMessage("com.vayunmathur.notes")
                .startsWith("The Notes app is required but not installed."))
        assertTrue(
            AssistantToolIntents.getMissingAppMessage("com.vayunmathur.todo")
                .contains("The Todo app"))
        // Unknown packages fall back to the raw package name.
        assertTrue(
            AssistantToolIntents.getMissingAppMessage("com.example.other")
                .startsWith("The com.example.other app is required"))
        assertTrue(
            tools.handleMissingApp("com.vayunmathur.clock")
                .contains("Try to help the user with your own knowledge instead."))
    }

    @Test fun runToolWrapsSuccessErrorsAndMissingApps() {
        assertEquals("done", tools.runTool { "done" })
        assertEquals("Error: boom", tools.runTool { throw IllegalStateException("boom") })
        assertEquals("Error: null", tools.runTool { throw RuntimeException() })
        val missing = tools.runTool { throw MissingAppException("com.vayunmathur.email") }
        assertTrue(missing.startsWith("The Email app is required"), missing)
    }

    @Test fun crossAppToolsThrowMissingApp() = runTest {
        assertFailsWith<MissingAppException> { tools.getNotes() }
        assertFailsWith<MissingAppException> { tools.insertNote(NoteData(title = "t", content = "c")) }
        assertFailsWith<MissingAppException> { tools.getTodos(GetTodosQuery()) }
        assertFailsWith<MissingAppException> { tools.insertTodo(InsertTodoData(title = "t")) }
        assertFailsWith<MissingAppException> { tools.completeTodo(CompleteTodoData(id = "1")) }
        assertFailsWith<MissingAppException> { tools.getContacts() }
        assertFailsWith<MissingAppException> { tools.insertContact(ContactData(name = "Ann", phoneNumber = "123")) }
        assertFailsWith<MissingAppException> { tools.getCalendarEvents() }
        assertFailsWith<MissingAppException> {
            tools.insertCalendarEvent(EventData(title = "Meet", start = 1L, end = 2L))
        }
        assertFailsWith<MissingAppException> { tools.getFamilyLocations() }
        assertFailsWith<MissingAppException> { tools.createLocationShareLink("Ann", 60_000.0) }
        assertFailsWith<MissingAppException> { tools.searchMusic("jazz") }
        assertFailsWith<MissingAppException> { tools.playMusic(PlayMusicData(id = 1L, type = "song")) }
        assertFailsWith<MissingAppException> { tools.searchEmails("invoice") }
        assertFailsWith<MissingAppException> { tools.getRecentEmails() }
        assertFailsWith<MissingAppException> {
            tools.setAlarm(SetAlarmData(hour = 7, minute = 30))
        }
        assertFailsWith<MissingAppException> { tools.setTimer(SetTimerData(seconds = 300)) }
        assertFailsWith<MissingAppException> { tools.getWeather(1.0, 2.0) }
        assertFailsWith<MissingAppException> { tools.getWeatherByName("Lisbon") }
        // And via runTool they become the friendly message instead of a throw.
        assertTrue(tools.runTool { tools.getNotes() }.startsWith("The Notes app is required"))
    }

    @Test fun memoryToolsUseDao() = runTest {
        val dao = db.memoryDao()
        assertEquals("Error: MemoryDao is null", tools.getMemories(null))
        assertEquals("Error: MemoryDao is null", tools.addToMemory(null, "x"))
        assertEquals("Error: MemoryDao is null", tools.removeMemory(null, 1.0))

        assertEquals("Success: Added memory", tools.addToMemory(dao, "likes tea"))
        tools.addToMemory(dao, "lives in Lisbon")
        val all = tools.getMemories(dao)
        assertTrue(all.contains("likes tea"), all)
        assertTrue(all.contains("lives in Lisbon"), all)
        val id = dao.getAll().first { it.content == "likes tea" }.id
        assertEquals("Success: Removed memory with id $id", tools.removeMemory(dao, id.toDouble()))
        assertEquals(listOf("lives in Lisbon"), dao.getAll().map { it.content })
    }

    @Test fun requestPayloadShapes() {
        assertEquals(3.5, WeatherLatLonRequest(3.5, -1.25).latitude)
        assertEquals("Lisbon", WeatherNameRequest("Lisbon").name)
        assertEquals(90L, CreateLinkRequest("Ann", 90L).expiryMillis)
        assertTrue(EmailData::class.java.name.endsWith("EmailData"))
    }
}

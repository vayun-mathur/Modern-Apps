package com.vayunmathur.todo

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.todo.data.TestTodoRepository
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.TodoSettings
import com.vayunmathur.todo.domain.TodoView
import com.vayunmathur.todo.platform.IntentRouter
import com.vayunmathur.todo.platform.TodoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [Navigation] + [com.vayunmathur.todo.ui.IntentEffect] + [MainActivity]
 * routing: the real nav graph composes the list page, consumes pending
 * [IntentRouter] requests once, and the activity records its launch intent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NavigationAndMainActivityTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private var harness: TestTodoRepository? = null
    private val repo get() = harness!!.repository

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        harness = TestTodoRepository.create(context)
        IntentRouter.consume()
    }

    @AfterTest fun tearDown() {
        IntentRouter.consume()
        harness?.close()
        harness = null
        Dispatchers.resetMain()
    }

    private fun viewModel() = TodoViewModel(app, repo, TodoSettings(context), SavedStateHandle())

    private fun seed() = runBlocking {
        repo.upsert(Todo(syncId = "nav1", title = "Nav alpha task"))
        repo.upsert(Todo(syncId = "nav2", title = "Nav beta task"))
    }

    @Test fun navigationComposesListPageFromRealViewModel() {
        seed()
        // The initial Route.TodoList is the settings default view.
        runBlocking { TodoSettings(context).setDefaultView(TodoView.All) }
        val vm = viewModel()
        rule.setContent { DynamicTheme { Navigation(vm) } }
        rule.onNodeWithText("Nav alpha task").assertIsDisplayed()
        rule.onNodeWithText("Nav beta task").assertIsDisplayed()
    }

    @Test fun intentEffectOpensTaskRequestedBeforeComposition() {
        seed()
        val vm = viewModel()
        val id = runBlocking { repo.getBySyncId("nav1")!!.id }
        IntentRouter.handle(
            Intent().putExtra(IntentRouter.EXTRA_OPEN_TASK, "nav1"),
        )
        assertTrue(IntentRouter.pending.value is IntentRouter.Request.OpenTask)
        rule.setContent { DynamicTheme { Navigation(vm) } }
        // The detail page for the requested task composes and the request
        // is consumed exactly once.
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("Nav alpha task").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Nav alpha task").assertIsDisplayed()
        assertNull(IntentRouter.pending.value)
        assertTrue(id > 0)
    }

    @Test fun intentEffectOpensViewAndQuickAddRequests() {
        seed()
        val vm = viewModel()
        IntentRouter.handle(Intent().putExtra(IntentRouter.EXTRA_VIEW, TodoView.All.key))
        rule.setContent { DynamicTheme { Navigation(vm) } }
        rule.waitUntil(timeoutMillis = 5_000) { IntentRouter.pending.value == null }
        // OpenView reset the stack to Route.TodoList(All.key), so both undated
        // seeded tasks are visible (the dedicated route test below pins the
        // exact viewKey round-trip).
        rule.onNodeWithText("Nav alpha task").assertIsDisplayed()
        rule.onNodeWithText("Nav beta task").assertIsDisplayed()
        // A later quick-add request resets to the list and pre-fills the sheet.
        rule.runOnIdle {
            IntentRouter.handle(
                Intent(IntentRouter.ACTION_QUICK_ADD).putExtra(Intent.EXTRA_TEXT, "Quick nav task"),
            )
        }
        rule.waitUntil(timeoutMillis = 5_000) { IntentRouter.pending.value == null }
        rule.onNodeWithText("Quick nav task").assertExists()
    }

    @Test fun routeTypesAreDistinctNavKeys() {
        assertEquals(Route.TodoList(TodoView.All.key), Route.TodoList("all"))
        assertTrue(Route.TodoList(TodoView.All.key) != Route.TodoList(TodoView.Inbox.key))
        // viewKey round-trips through TodoView and through serialization.
        val route = Route.TodoList(TodoView.InList("work").key)
        assertEquals(TodoView.InList("work"), TodoView.fromKey(route.viewKey))
        assertEquals(route, Json.decodeFromString<Route.TodoList>(Json.encodeToString(route)))
        assertEquals(Route.Settings, Route.Settings)
        assertEquals(Route.TodoDetail(7L, "sync"), Route.TodoDetail(7L, "sync"))
        assertTrue(Route.TodoDetail(7L) != Route.TodoDetail(8L))
        assertEquals(null, Route.TodoDetail(7L).newSyncId)
    }

    @Test fun mainActivityRecordsLaunchIntentAndComposesNavigation() {
        seed()
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(IntentRouter.EXTRA_VIEW, TodoView.All.key)
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        // The activity's own Navigation graph consumes the recorded view
        // request as soon as it composes.
        // Navigation waits for the settings default view before creating the
        // retained stack, so consumption is asynchronous.
        rule.waitUntil(timeoutMillis = 5_000) { IntentRouter.pending.value == null }
        // A fresh intent arriving while alive is recorded the same way and
        // likewise recorded by the live activity (its graph may or may not
        // have a frame in flight under Robolectric to consume it yet).
        controller.newIntent(Intent(context, MainActivity::class.java).setAction(IntentRouter.ACTION_QUICK_ADD))
        // Recording is synchronous in onNewIntent; consumption happens on a
        // later composition frame.
        assertTrue(IntentRouter.pending.value is IntentRouter.Request.QuickAdd)
    }
}

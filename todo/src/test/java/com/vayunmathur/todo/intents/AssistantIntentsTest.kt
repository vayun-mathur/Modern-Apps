package com.vayunmathur.todo.intents

import android.content.Context
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.action.actionParametersOf
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.intents.todo.CompleteTodoData
import com.vayunmathur.library.intents.todo.GetTodosQuery
import com.vayunmathur.library.intents.todo.InsertTodoData
import com.vayunmathur.todo.data.TestTodoRepository
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.TodoList
import com.vayunmathur.todo.data.getLists
import com.vayunmathur.todo.data.upsertList
import com.vayunmathur.todo.widget.CompleteTaskAction
import com.vayunmathur.todo.widget.QuickAddWidget
import com.vayunmathur.todo.widget.TodayWidget
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Assistant-tool ([InsertTodoIntent]/[GetTodosIntent]/[CompleteTodoIntent]) and
 * widget data-path behaviour against a real in-memory repository installed as
 * the [com.vayunmathur.todo.data.TodoRepository] singleton, which is how the
 * production activities/widgets resolve it. Glance rendering itself is not
 * exercised here (device/host-bound); the pure data loading, preview data and
 * the checkbox action callback are.
 */
@RunWith(RobolectricTestRunner::class)
class AssistantIntentsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var harness: TestTodoRepository? = null
    private val repo get() = harness!!.repository

    @BeforeTest fun setUp() { harness = TestTodoRepository.create(context) }
    @AfterTest fun tearDown() { harness?.close(); harness = null }

    // Widget classes are final and loadData/previewData are protected on
    // MaWidget, so the genuine data path is invoked reflectively; Glance
    // rendering itself is host-bound and not exercised.
    private fun findMethod(widget: Any, method: String): java.lang.reflect.Method {
        var type: Class<*>? = widget.javaClass
        while (type != null) {
            type.declaredMethods.firstOrNull { it.name == method }?.let { it.isAccessible = true; return it }
            type = type.superclass
        }
        error("No $method on ${widget.javaClass}")
    }

    private fun <T> callWidget(widget: Any, method: String, vararg args: Any?): T {
        @Suppress("UNCHECKED_CAST")
        return findMethod(widget, method).invoke(widget, *args) as T
    }

    /** Reflectively calls a suspend widget method, blocking until it resumes. */
    private fun <T> blockingWidget(widget: Any, method: String, vararg args: Any?): T {
        val latch = java.util.concurrent.CountDownLatch(1)
        var outcome: Result<T>? = null
        val cont = object : kotlin.coroutines.Continuation<T> {
            override val context = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { outcome = result; latch.countDown() }
        }
        val returned = findMethod(widget, method).invoke(widget, *args, cont)
        if (returned != kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) {
            @Suppress("UNCHECKED_CAST")
            outcome = Result.success(returned as T)
            latch.countDown()
        }
        assertTrue(latch.await(10, java.util.concurrent.TimeUnit.SECONDS), "widget $method timed out")
        return outcome!!.getOrThrow()
    }

    // --- InsertTodoIntent ---

    @Test fun insertPlainTitleCreatesTaskAndReturnsSyncId() = runBlocking {
        val syncId = InsertTodoIntent().performCalculation(InsertTodoData(title = "Buy milk", notes = "oat"))
        val stored = repo.getBySyncId(syncId)
        assertNotNull(stored)
        assertEquals("Buy milk", stored.title)
        assertEquals("oat", stored.notes)
        assertNull(stored.dueAt)
        assertEquals(0, stored.priority)
    }

    @Test fun insertHonoursQuickAddSyntaxWhenNoDueGiven(): Unit = runBlocking {
        repo.upsertList(TodoList(syncId = "home", name = "Home"))
        val syncId = InsertTodoIntent().performCalculation(InsertTodoData(title = "Pay rent @home !1 tomorrow"))
        val stored = repo.getBySyncId(syncId)!!
        assertEquals("Pay rent", stored.title)
        assertEquals(1, stored.priority)
        assertEquals("home", stored.listSyncId)
        assertNotNull(stored.dueAt)
    }

    @Test fun insertExplicitDueAndPriorityOverrideQuickAdd() = runBlocking {
        val due = System.currentTimeMillis() + 86_400_000L
        val syncId = InsertTodoIntent().performCalculation(
            InsertTodoData(title = "Call @nobody tomorrow", dueAt = due, priority = 3),
        )
        val stored = repo.getBySyncId(syncId)!!
        // Explicit due/priority: title is used verbatim, no parsed tags/list.
        assertEquals("Call @nobody tomorrow", stored.title)
        assertEquals(due, stored.dueAt)
        assertEquals(3, stored.priority)
        assertFalse(stored.dueAllDay)
        assertTrue(stored.tags.isEmpty())
        assertNull(stored.listSyncId)
    }

    @Test fun insertOutOfRangePriorityFallsBackToParsedOrZero() = runBlocking {
        val a = repo.getBySyncId(InsertTodoIntent().performCalculation(InsertTodoData(title = "Low", priority = 99)))!!
        assertEquals(0, a.priority)
        val b = repo.getBySyncId(InsertTodoIntent().performCalculation(InsertTodoData(title = "High !2", priority = -1)))!!
        assertEquals(2, b.priority)
    }

    @Test fun insertBlankTitleFallsBackToTrimmedInput() = runBlocking {
        // Quick-add tokens only: parsed title is empty, so the input is kept.
        val syncId = InsertTodoIntent().performCalculation(InsertTodoData(title = "tomorrow"))
        assertEquals("tomorrow", repo.getBySyncId(syncId)!!.title)
    }

    // --- GetTodosIntent ---

    private suspend fun seedForGet() {
        repo.upsertList(TodoList(syncId = "work", name = "Work"))
        val now = System.currentTimeMillis()
        repo.upsert(Todo(syncId = "today", title = "Today task", dueAt = now, listSyncId = "work", priority = 2, notes = "n1"))
        repo.upsert(Todo(syncId = "future", title = "Future task", dueAt = now + 7 * 86_400_000L))
        repo.upsert(Todo(syncId = "nodate", title = "No date task"))
        repo.upsert(Todo(syncId = "done", title = "Done task", dueAt = now, done = true, completedAt = now))
    }

    @Test fun getTodosTodayReturnsOnlyTodaysOpenTasks() = runBlocking {
        seedForGet()
        val out = GetTodosIntent().performCalculation(GetTodosQuery(scope = "today"))
        assertEquals(listOf("Today task"), out.map { it.title })
        assertEquals("Work", out.single().list)
        assertEquals(2, out.single().priority)
        assertEquals("n1", out.single().notes)
        assertFalse(out.single().done)
    }

    @Test fun getTodosUpcomingAndAllAndUnknownScope() = runBlocking {
        seedForGet()
        val upcoming = GetTodosIntent().performCalculation(GetTodosQuery(scope = "UPCOMING"))
        assertTrue(upcoming.any { it.title == "Future task" })
        val all = GetTodosIntent().performCalculation(GetTodosQuery(scope = "all"))
        assertTrue(all.map { it.title }.containsAll(listOf("Today task", "Future task", "No date task")))
        // Unknown scope defaults to Today, case-insensitively.
        val fallback = GetTodosIntent().performCalculation(GetTodosQuery(scope = "whatever"))
        assertEquals(listOf("Today task"), fallback.map { it.title })
        val empty = GetTodosIntent().performCalculation(GetTodosQuery(scope = ""))
        assertEquals(listOf("Today task"), empty.map { it.title })
    }

    @Test fun getTodosEmptyRepoReturnsEmpty() = runBlocking {
        assertTrue(GetTodosIntent().performCalculation(GetTodosQuery()).isEmpty())
        assertEquals(0, repo.getLists().size)
    }

    // --- CompleteTodoIntent ---

    @Test fun completeByIdMarksDoneAndIsIdempotent() = runBlocking {
        repo.upsert(Todo(syncId = "c1", title = "Finish me"))
        assertTrue(CompleteTodoIntent().performCalculation(CompleteTodoData(id = "c1")))
        assertTrue(repo.getBySyncId("c1")!!.done)
        // Already done via id path: still true, and title path skips done rows.
        assertTrue(CompleteTodoIntent().performCalculation(CompleteTodoData(id = "c1")))
    }

    @Test fun completeByTitleCaseInsensitiveTrimsAndSkipsDoneAndDeleted() = runBlocking {
        repo.upsert(Todo(syncId = "done1", title = "Report", done = true, completedAt = 1L))
        repo.upsert(Todo(syncId = "open1", title = "Report"))
        repo.upsert(Todo(syncId = "del1", title = "Ghost", deleted = true))
        assertTrue(CompleteTodoIntent().performCalculation(CompleteTodoData(title = "  report ")))
        assertTrue(repo.getBySyncId("open1")!!.done)
        // Deleted row is not matched by id, and no open "Ghost" exists.
        assertFalse(CompleteTodoIntent().performCalculation(CompleteTodoData(id = "del1", title = "Ghost")))
        assertFalse(repo.getBySyncId("del1")!!.done)
    }

    @Test fun completeMissingReturnsFalse() = runBlocking {
        assertFalse(CompleteTodoIntent().performCalculation(CompleteTodoData(id = "nope", title = "Nope")))
        assertFalse(CompleteTodoIntent().performCalculation(CompleteTodoData()))
    }

    @Test fun completeBlankIdFallsThroughToTitle() = runBlocking {
        repo.upsert(Todo(syncId = "b1", title = "Blank id task"))
        assertTrue(CompleteTodoIntent().performCalculation(CompleteTodoData(id = "  ", title = "Blank id task")))
        assertTrue(repo.getBySyncId("b1")!!.done)
    }

    // --- Widgets: data path only (Glance rendering skipped, host-bound) ---

    @Test fun todayWidgetLoadsTodaysRowsWithOverdueFlag() = runBlocking {
        val now = System.currentTimeMillis()
        repo.upsert(Todo(syncId = "over", title = "Overdue", dueAt = now - 86_400_000L))
        repo.upsert(Todo(syncId = "today", title = "Today", dueAt = now))
        repo.upsert(Todo(syncId = "future", title = "Future", dueAt = now + 86_400_000L))
        repo.upsert(Todo(syncId = "done", title = "Done", dueAt = now, done = true, completedAt = now))
        val data: com.vayunmathur.todo.widget.TodayWidgetData =
            blockingWidget(TodayWidget(), "loadData", context, AppWidgetId(11))
        assertEquals(setOf("Overdue", "Today"), data.rows.map { it.title }.toSet())
        assertTrue(data.rows.first { it.syncId == "over" }.overdue)
        assertFalse(data.rows.first { it.syncId == "today" }.overdue)
    }

    @Test fun todayWidgetPreviewHasThreeRowsAndQuickWidgetLoadsUnit() = runBlocking {
        val preview: com.vayunmathur.todo.widget.TodayWidgetData = callWidget(TodayWidget(), "previewData", context)
        assertEquals(3, preview.rows.size)
        assertTrue(preview.rows.first().overdue)
        assertFalse(preview.rows.last().overdue)
        val quick: Unit = blockingWidget(QuickAddWidget(), "loadData", context, AppWidgetId(12))
        assertEquals(Unit, quick)
        assertEquals(Unit, callWidget<Unit>(QuickAddWidget(), "previewData", context))
    }

    @Test fun widgetProvideGlanceAndPreviewRunWithoutCrashing() = runBlocking {
        repo.upsert(Todo(syncId = "wg1", title = "Glance task", dueAt = System.currentTimeMillis()))
        // MaWidget.provideGlance/providePreview compose Content internally;
        // under Robolectric there is no widget host, so this is a smoke entry
        // (precedent: calendar's Glance widget tests) — Glance rendering to
        // RemoteViews remains host-bound and is not asserted here.
        TodayWidget().providePreview(context, 0)
        TodayWidget().provideGlance(context, AppWidgetId(21))
        QuickAddWidget().providePreview(context, 0)
        QuickAddWidget().provideGlance(context, AppWidgetId(22))
    }

    @Test fun completeTaskActionCompletesAndIgnoresMissingOrDone() = runBlocking {
        repo.upsert(Todo(syncId = "w1", title = "Widget task"))
        repo.upsert(Todo(syncId = "w2", title = "Already done", done = true, completedAt = 1L))
        val action = CompleteTaskAction()
        action.onAction(context, AppWidgetId(13), actionParametersOf(CompleteTaskAction.SYNC_ID to "w1"))
        assertTrue(repo.getBySyncId("w1")!!.done)
        action.onAction(context, AppWidgetId(13), actionParametersOf(CompleteTaskAction.SYNC_ID to "w2"))
        action.onAction(context, AppWidgetId(13), actionParametersOf(CompleteTaskAction.SYNC_ID to "missing"))
        // No SYNC_ID parameter at all: returns without touching anything.
        action.onAction(context, AppWidgetId(13), actionParametersOf())
        assertEquals(1, repo.getAll().count { it.done && it.syncId == "w2" })
    }
}

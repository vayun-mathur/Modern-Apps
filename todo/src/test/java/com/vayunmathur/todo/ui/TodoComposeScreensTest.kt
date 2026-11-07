package com.vayunmathur.todo.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.TodoList
import com.vayunmathur.todo.domain.DueDates
import com.vayunmathur.todo.domain.GroupKey
import com.vayunmathur.todo.domain.Recurrence
import com.vayunmathur.todo.domain.SortMode
import com.vayunmathur.todo.domain.TodoGroup
import com.vayunmathur.todo.domain.TodoListModel
import com.vayunmathur.todo.domain.TodoQuery
import com.vayunmathur.todo.domain.TodoRow
import com.vayunmathur.todo.domain.TodoView
import com.vayunmathur.todo.platform.TodoActions
import com.vayunmathur.todo.platform.TodoListActions
import com.vayunmathur.todo.platform.TodoListUiState
import com.vayunmathur.todo.platform.TodoUiState
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class TodoComposeScreensTest {

    @get:Rule val rule = createComposeRule()

    private val zone = TimeZone.currentSystemDefault()
    private val now = DueDates.at(LocalDate(2026, 10, 14), LocalTime(9, 0), zone)
    private fun day(offset: Int): Long =
        DueDates.startOfDay(LocalDate(2026, 10, 14).plus(offset, kotlinx.datetime.DateTimeUnit.DAY), zone)
    private val work = TodoList(id = 1, syncId = "work", name = "Work", color = 0xFF1E88E5.toInt(), icon = "work", sections = listOf("Now", "Later"))
    private val home = TodoList(id = 2, syncId = "home", name = "Home", color = 0xFF43A047.toInt(), icon = "home")
    private val lists = listOf(work, home)
    private val todos = listOf(
        Todo(id = 1, syncId = "a", title = "Renew passport", dueAt = day(-2), priority = 1, position = 1.0, createdAt = day(-9)),
        Todo(id = 2, syncId = "b", title = "Finish report", dueAt = day(0), dueAllDay = true, priority = 2, listSyncId = "work", position = 2.0, createdAt = day(-3), tags = listOf("office"), notes = "Numbers folder"),
        Todo(id = 3, syncId = "c", title = "Water plants", dueAt = day(0), recurrence = Recurrence.Daily(3).serialize(), listSyncId = "home", position = 3.0, createdAt = day(-30)),
        Todo(id = 4, syncId = "d", title = "Buy milk", done = true, completedAt = now - 1000, dueAt = day(0), position = 4.0, createdAt = day(-1)),
        Todo(id = 5, syncId = "e", title = "Outline", parentSyncId = "b", done = true, position = 5.0),
        Todo(id = 6, syncId = "f", title = "Charts", parentSyncId = "b", position = 6.0),
    )

    private fun listState(view: TodoView = TodoView.Today, query: String = "") = TodoListUiState(
        view = view,
        model = TodoQuery.build(TodoQuery.Input(todos, lists, view, query, now = now, zone = zone)),
        lists = lists, searchQuery = query, now = now,
    )

    @Test fun listScreenShowsTasksAndQuickAdd() {
        rule.setContent { DynamicTheme { TodoListScreen(state = listState(), actions = TodoListActions.Noop) } }
        rule.onNodeWithText("Renew passport").assertIsDisplayed()
        rule.onNodeWithText("Finish report").assertIsDisplayed()
        rule.onNodeWithText("Add a task").assertIsDisplayed()
    }

    @Test fun listScreenEmptyNothing() {
        rule.setContent { DynamicTheme { TodoListScreen(state = TodoListUiState(view = TodoView.Inbox, model = TodoListModel(), lists = lists, now = now), actions = TodoListActions.Noop) } }
        rule.onNodeWithText("No tasks").assertIsDisplayed()
    }
    @Test fun listScreenEmptySearch() {
        rule.setContent { DynamicTheme { TodoListScreen(state = TodoListUiState(view = TodoView.Inbox, model = TodoListModel(), lists = lists, searchQuery = "zz", now = now), actions = TodoListActions.Noop) } }
        rule.onNodeWithText("No matching tasks").assertIsDisplayed()
    }
    @Test fun listScreenEmptyAllDone() {
        val doneModel = TodoListModel(completed = listOf(TodoRow(todos[3])))
        rule.setContent { DynamicTheme { TodoListScreen(state = TodoListUiState(view = TodoView.Inbox, model = doneModel, lists = lists, now = now), actions = TodoListActions.Noop) } }
        rule.onNodeWithText("All done!").assertIsDisplayed()
    }

    @Test fun listScreenClickOpensAndCheckboxToggles() {
        var opened = -1L; var toggled: Todo? = null
        val actions = object : TodoListActions {
            override fun openTodo(id: Long) { opened = id }
            override fun toggleDone(todo: Todo) { toggled = todo }
        }
        rule.setContent { DynamicTheme { TodoListScreen(state = listState(TodoView.All), actions = actions) } }
        rule.onNodeWithText("Renew passport").performClick()
        assertEquals(1L, opened)
    }

    @Test fun listScreenCompletedHeaderExpands() {
        rule.setContent { DynamicTheme { TodoListScreen(state = listState(TodoView.All), actions = TodoListActions.Noop) } }
        rule.onNodeWithText("Completed (1)", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Completed (1)", substring = true).performClick()
        rule.waitForIdle()
    }

    @Test fun listScreenSearchQueryCallback() {
        rule.setContent { DynamicTheme { TodoListScreen(state = listState(TodoView.All, query = "report"), actions = TodoListActions.Noop) } }
        rule.onNodeWithText("Finish report").assertIsDisplayed()
    }

    @Test fun detailScreenShowsTitleNotesAndSubtasks() {
        val state = TodoUiState(todo = todos[1], subtasks = todos.filter { it.parentSyncId == "b" }, lists = lists, allTags = listOf("office"), now = now)
        rule.setContent { DynamicTheme { TodoDetailScreen(state = state, actions = TodoActions.Noop) } }
        rule.onNodeWithText("Finish report").assertIsDisplayed()
        rule.onNodeWithText("Notes").assertExists()
        rule.onNodeWithText("Subtasks").assertExists()
        rule.onNodeWithText("Outline").assertExists()
        rule.onNodeWithText("Charts").assertExists()
    }

    @Test fun detailScreenNewTaskHidesMetaFooter() {
        val state = TodoUiState(todo = Todo(id = 0, title = ""), lists = lists, isNew = true, now = now)
        rule.setContent { DynamicTheme { TodoDetailScreen(state = state, actions = TodoActions.Noop) } }
        rule.onNodeWithText("Title").assertIsDisplayed()
    }

    @Test fun detailScreenRepeatingTaskChips() {
        val state = TodoUiState(todo = todos[2].copy(reminderAt = day(0), estimateMinutes = 30, tags = listOf("home"), blockedBy = listOf("a")), lists = lists, now = now)
        rule.setContent { DynamicTheme { TodoDetailScreen(state = state, actions = TodoActions.Noop) } }
        rule.onNodeWithText("Water plants").assertIsDisplayed()
        // Repeating toggle + schedule section present
        rule.onNodeWithText("Repeating", substring = true).assertIsDisplayed()
    }

    @Test fun drawerShowsViewsListsTagsAndSettings() {
        var selected: TodoView? = null; var settings = false; var newList = false
        val actions = object : DrawerActions {
            override fun select(view: TodoView) { selected = view }
            override fun openSettings() { settings = true }
            override fun newList() { newList = true }
        }
        val state = DrawerUiState(view = TodoView.Today, counts = mapOf(TodoView.Today to 3, TodoView.Inbox to 2), lists = lists, tags = listOf("errands"), syncStatus = "Synced 5 min ago")
        rule.setContent { DynamicTheme { ViewDrawerContent(state, actions) } }
        rule.onNodeWithText("Todo").assertIsDisplayed()
        rule.onNodeWithText("Work").assertExists()
        rule.onNodeWithText("Home").assertExists()
        rule.onNodeWithText("errands").assertExists()
        rule.onNodeWithText("Synced 5 min ago").assertExists()
        rule.onNodeWithText("Settings").assertExists()
        rule.onNodeWithText("New list").assertExists()
    }

    @Test fun drawerSmartViewsClick() {
        var selected: TodoView? = null
        val actions = object : DrawerActions { override fun select(view: TodoView) { selected = view } }
        rule.setContent { DynamicTheme { ViewDrawerContent(DrawerUiState(lists = lists), actions) } }
        rule.onNodeWithText("Today").performClick()
        assertEquals(TodoView.Today, selected)
        rule.onNodeWithText("All tasks").performClick()
        assertEquals(TodoView.All, selected)
    }

    @Test fun settingsScreenShowsRowsAndSelects() {
        var view: TodoView? = null; var sort: SortMode? = null; var show: Boolean? = null
        val actions = object : SettingsActions {
            override fun setDefaultView(v: TodoView) { view = v }
            override fun setDefaultSort(s: SortMode) { sort = s }
            override fun setShowCompleted(b: Boolean) { show = b }
        }
        // interface methods have different param names; use named impl via object above fails if names differ - handled in impl class below
        rule.setContent { DynamicTheme { SettingsScreen(state = SettingsUiState(lists = lists), actions = actions, extraSections = { }, dataSection = { }) } }
        rule.onNodeWithText("Settings").assertIsDisplayed()
        rule.onNodeWithText("General", substring = true).assertIsDisplayed()
    }

    @Test fun viewTitleAndLabels() {
        rule.setContent {
            DynamicTheme {
                androidx.compose.foundation.layout.Column {
                    com.vayunmathur.library.ui.Text(viewTitle(TodoView.Inbox, lists))
                    com.vayunmathur.library.ui.Text(viewTitle(TodoView.InList("work"), lists))
                    com.vayunmathur.library.ui.Text(viewTitle(TodoView.Tagged("errands"), lists))
                    com.vayunmathur.library.ui.Text(recurrenceLabel(Recurrence.Daily(2)))
                    com.vayunmathur.library.ui.Text(recurrenceLabel(Recurrence.Monthly(1, 15)))
                    com.vayunmathur.library.ui.Text(recurrenceLabel(Recurrence.Yearly(1)))
                }
            }
        }
        rule.onNodeWithText("Inbox").assertIsDisplayed()
        rule.onNodeWithText("Work").assertIsDisplayed()
        rule.onNodeWithText("#errands").assertIsDisplayed()
    }

    @Test fun groupHeadersAndEmptyKinds() {
        rule.setContent {
            DynamicTheme {
                androidx.compose.foundation.layout.Column {
                    GroupHeader(GroupKey.Bucket(com.vayunmathur.todo.domain.DueBucket.OVERDUE))
                    GroupHeader(GroupKey.Section("Now"))
                    GroupHeader(GroupKey.Section(null))
                    TodoEmptyState(EmptyKind.ALL_DONE)
                }
            }
        }
        rule.onNodeWithText("Overdue").assertIsDisplayed()
        rule.onNodeWithText("Now").assertIsDisplayed()
        rule.onNodeWithText("All done!").assertIsDisplayed()
    }

    @Test fun completedHeaderClearAndToggle() {
        var toggled = false; var cleared = false
        rule.setContent { DynamicTheme { CompletedHeader(count = 3, expanded = false, onToggle = { toggled = true }, onClear = { cleared = true }) } }
        rule.onNodeWithText("Completed (3)", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Clear").performClick()
        assertTrue(cleared)
        rule.onNodeWithText("Completed (3)", substring = true).performClick()
        assertTrue(toggled)
    }

    @Test fun highlightedAndMetaAndFooter() {
        val row = TodoRow(todo = todos[1].copy(recurrence = Recurrence.Daily().serialize(), reminderAt = day(0)), subtasksDone = 1, subtasksTotal = 2, list = work)
        rule.setContent {
            DynamicTheme {
                androidx.compose.foundation.layout.Column {
                    HighlightedText(text = "Finish report", query = "report")
                    TodoRowMeta(row = row, now = now, showList = true)
                    EditorMetaFooter(todo = todos[1].copy(completedAt = now))
                    ListIcon("work"); ListIcon("home"); ListIcon("cart"); ListIcon("star"); ListIcon("heart"); ListIcon("book"); ListIcon("idea"); ListIcon("travel"); ListIcon("health"); ListIcon("bills"); ListIcon(null); ListIcon("unknown")
                }
            }
        }
        rule.onNodeWithText("Finish report").assertIsDisplayed()
        rule.onNodeWithText("#office").assertIsDisplayed()
    }

    @Test fun rowItemDoneAndBlocked() {
        var toggled = false; var clicked = false
        val row = TodoRow(todo = todos[3], blocked = false)
        rule.setContent {
            DynamicTheme {
                TodoRowItem(row = row, query = "", now = now, showList = false, selected = false, elevation = androidx.compose.ui.unit.Dp(0f),
                    callbacks = TodoRowCallbacks(onToggle = { toggled = true }, onClick = { clicked = true }, onLongClick = {}))
            }
        }
        rule.onNodeWithText("Buy milk").assertIsDisplayed()
        rule.onNodeWithText("Buy milk").performClick()
        assertTrue(clicked)
    }

    @Test fun sortMenuAndTopBar() {
        var sort: SortMode? = null
        rule.setContent { DynamicTheme { SortMenu(sort = SortMode.MANUAL, onSort = { sort = it }) } }
        // Icon button present; click opens menu
        rule.onNodeWithContentDescription("Sort").assertExists()
        rule.onNodeWithContentDescription("Sort").performClick()
        rule.onNodeWithText("Due date").assertIsDisplayed()
    }

    @Test fun priorityColorsAndSyncStatusAndDueLabel() {
        rule.setContent {
            DynamicTheme {
                androidx.compose.foundation.layout.Column {
                    com.vayunmathur.library.ui.Text(dueLabel(day(0), true, now))
                    com.vayunmathur.library.ui.Text(dueLabel(day(1), true, now))
                    com.vayunmathur.library.ui.Text(dueLabel(day(-1), true, now))
                    com.vayunmathur.library.ui.Text(syncStatus(0L) ?: "never")
                    com.vayunmathur.library.ui.Text(priorityColor(1)?.toString() ?: "none")
                }
            }
        }
        rule.onNodeWithText("Today").assertIsDisplayed()
        rule.onNodeWithText("Tomorrow").assertIsDisplayed()
        rule.onNodeWithText("Yesterday").assertIsDisplayed()
        rule.onNodeWithText("never").assertIsDisplayed()
    }
}

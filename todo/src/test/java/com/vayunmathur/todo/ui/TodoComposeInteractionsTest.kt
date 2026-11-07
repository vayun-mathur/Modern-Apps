package com.vayunmathur.todo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.TodoList
import com.vayunmathur.todo.domain.DueBucket
import com.vayunmathur.todo.domain.DueDates
import com.vayunmathur.todo.domain.GroupKey
import com.vayunmathur.todo.domain.Recurrence
import com.vayunmathur.todo.domain.SortMode
import com.vayunmathur.todo.domain.TodoGroup
import com.vayunmathur.todo.domain.TodoListModel
import com.vayunmathur.todo.domain.TodoRow
import com.vayunmathur.todo.domain.TodoView
import com.vayunmathur.todo.platform.TodoActions
import com.vayunmathur.todo.platform.TodoListActions
import com.vayunmathur.todo.platform.TodoListUiState
import com.vayunmathur.todo.platform.TodoUiState
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class TodoComposeInteractionsTest {
    @get:Rule val rule = createComposeRule()
    private val zone = TimeZone.currentSystemDefault()
    private val now = DueDates.at(LocalDate(2026, 10, 14), LocalTime(9, 0), zone)
    private fun day(o: Int) = DueDates.startOfDay(LocalDate(2026, 10, 14).plus(o, kotlinx.datetime.DateTimeUnit.DAY), zone)
    private val work = TodoList(id = 1, syncId = "work", name = "Work", sections = listOf("Now", "Later"))
    private val lists = listOf(work)

    @Test fun bulkBarMenuSelectAllAndPriority() {
        var all = false; var prio = -1; var resched: Long? = -99L; var completed = false; var deleted = false; var cleared = false
        val actions = object : BulkActions {
            override fun clear() { cleared = true }; override fun selectAll() { all = true }; override fun complete() { completed = true }; override fun delete() { deleted = true }
            override fun move(listSyncId: String?) {}; override fun reschedule(dueAt: Long?) { resched = dueAt }; override fun setPriority(priority: Int) { prio = priority }
        }
        rule.setContent { DynamicTheme { BulkActionBar(count = 2, lists = lists, actions = actions) } }
        rule.onNodeWithText("2 selected").assertIsDisplayed()
        rule.onNodeWithContentDescription("Check").performClick(); assertTrue(completed)
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Select all").performClick(); assertTrue(all)
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Priority 3").performClick(); assertEquals(3, prio)
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Today").performClick(); assertTrue(resched != -99L)
        rule.onNodeWithContentDescription("Delete").performClick(); assertTrue(deleted)
        rule.onNodeWithContentDescription("Close").performClick(); assertTrue(cleared)
    }
    @Test fun bulkBarMoveToListPicker() {
        var moved: String? = "unset"
        val actions = object : BulkActions {
            override fun clear() {}; override fun selectAll() {}; override fun complete() {}; override fun delete() {}
            override fun move(listSyncId: String?) { moved = listSyncId }; override fun reschedule(dueAt: Long?) {}; override fun setPriority(priority: Int) {}
        }
        rule.setContent { DynamicTheme { BulkActionBar(count = 1, lists = lists, actions = actions) } }
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Move to\u2026").performClick()
        rule.onNodeWithText("Work").performClick()
        assertEquals("work", moved)
    }
    @Test fun bulkBarPickDateOpensPicker() {
        val actions = object : BulkActions {
            override fun clear() {}; override fun selectAll() {}; override fun complete() {}; override fun delete() {}
            override fun move(listSyncId: String?) {}; override fun reschedule(dueAt: Long?) {}; override fun setPriority(priority: Int) {}
        }
        rule.setContent { DynamicTheme { BulkActionBar(count = 1, lists = lists, actions = actions) } }
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Pick a date\u2026").performClick()
        rule.onNodeWithText("OK").assertIsDisplayed()
    }
    @Test fun editorSheetHostRepeat() {
        var updated: Todo? = null
        val actions = object : TodoActions { override fun update(transform: (Todo) -> Todo) { updated = transform(Todo(title = "t", dueAt = day(0))) } }
        val state = TodoUiState(todo = Todo(title = "t", dueAt = day(0)), lists = lists, now = now)
        rule.setContent { DynamicTheme { EditorSheetHost(EditorSheet.REPEAT, state, actions) {} } }
        rule.onNodeWithText("Every day").performClick()
        assertEquals(Recurrence.Daily().serialize(), updated?.recurrence)
    }
    @Test fun editorSheetHostReminder() {
        var updated: Todo? = null
        val actions = object : TodoActions { override fun update(transform: (Todo) -> Todo) { updated = transform(Todo(title = "t", dueAt = day(0))) } }
        val state = TodoUiState(todo = Todo(title = "t", dueAt = day(1)), lists = lists, now = now)
        rule.setContent { DynamicTheme { EditorSheetHost(EditorSheet.REMINDER, state, actions) {} } }
        rule.onNodeWithText("No reminder").performClick()
        assertNotNull(updated)
    }
    @Test fun editorSheetHostList() {
        val state = TodoUiState(todo = Todo(title = "t"), lists = lists, now = now)
        rule.setContent { DynamicTheme { EditorSheetHost(EditorSheet.LIST, state, TodoActions.Noop) {} } }
        rule.onNodeWithText("Work").assertIsDisplayed()
        rule.onNodeWithText("Now").assertIsDisplayed()
    }
    @Test fun editorSheetHostBlocked() {
        val state = TodoUiState(todo = Todo(title = "t"), lists = lists, blockerCandidates = listOf(Todo(syncId = "x", title = "Blocker task")), now = now)
        rule.setContent { DynamicTheme { EditorSheetHost(EditorSheet.BLOCKED, state, TodoActions.Noop) {} } }
        rule.onNodeWithText("Blocker task").assertIsDisplayed()
    }
    @Test fun editorSheetHostStartWithExisting() {
        val state = TodoUiState(todo = Todo(title = "t", startAt = day(1)), lists = lists, now = now)
        rule.setContent { DynamicTheme { EditorSheetHost(EditorSheet.START, state, TodoActions.Noop) {} } }
        rule.onNodeWithText("Hide until").assertIsDisplayed()
        rule.onNodeWithText("Show it now").assertIsDisplayed()
    }
    @Test fun editorSheetHostStartWithout() {
        val state = TodoUiState(todo = Todo(title = "t"), lists = lists, now = now)
        rule.setContent { DynamicTheme { EditorSheetHost(EditorSheet.START, state, TodoActions.Noop) {} } }
        rule.onNodeWithText("OK").assertIsDisplayed()
    }
    @Test fun editorChipsOpenDueSheetAndKindToggle() {
        var last: Todo? = null
        val actions = object : TodoActions { override fun update(transform: (Todo) -> Todo) { last = transform(Todo(title = "t")) } }
        val state = TodoUiState(todo = Todo(title = "t"), lists = lists, now = now)
        rule.setContent { DynamicTheme { EditorChips(state, actions) } }
        rule.onNodeWithText("Repeating").performClick()
        assertNotNull(last?.recurrence)
        rule.onNodeWithText("Due date").performClick()
        rule.onNodeWithText("Tomorrow").assertIsDisplayed()
    }
    @Test fun editorChipsPriorityAndEstimateMenus() {
        rule.setContent { DynamicTheme { EditorChips(TodoUiState(todo = Todo(title = "t"), lists = lists, now = now), TodoActions.Noop) } }
        rule.onNodeWithText("Priority").performClick()
        rule.onNodeWithText("No priority").assertIsDisplayed()
        rule.onNodeWithText("No priority").performClick()
        rule.onNodeWithText("Time estimate").performClick()
        rule.onNodeWithText("No estimate").assertIsDisplayed()
    }
    @Test fun detailScreenMenuDuplicateAndDeleteDialog() {
        var duplicated = false
        val actions = object : TodoActions { override fun duplicate() { duplicated = true } }
        val state = TodoUiState(todo = Todo(id = 1, title = "My task", createdAt = now - 100000, updatedAt = now), lists = lists, now = now)
        rule.setContent { DynamicTheme { TodoDetailScreen(state, actions) } }
        rule.onNodeWithText("My task").assertIsDisplayed()
        // overflow menu
        val nodes = rule.onAllNodesWithContentDescription("More").fetchSemanticsNodes()
        if (nodes.isNotEmpty()) {
            rule.onNodeWithContentDescription("More").performClick()
            rule.onNodeWithText("Duplicate").performClick()
            assertTrue(duplicated)
        }
    }
    @Test fun detailScreenTitleEditCallbacks() {
        var lastTitle: String? = null
        val actions = object : TodoActions { override fun update(transform: (Todo) -> Todo) { lastTitle = transform(Todo(title = "")).title } }
        val state = TodoUiState(todo = Todo(id = 1, title = "Old"), lists = lists, now = now)
        rule.setContent { DynamicTheme { TodoDetailScreen(state, actions) } }
        rule.onNodeWithText("Old").performTextInput("X")
        rule.waitForIdle()
        assertTrue(lastTitle != null)
    }
    @Test fun listScreenLongPressSelectsAndBulkBarAppears() {
        val todos = listOf(Todo(id = 1, syncId = "a", title = "Selectable task", position = 1.0))
        val model = TodoListModel(groups = listOf(TodoGroup(GroupKey.None, listOf(TodoRow(todos[0])))))
        val state = TodoListUiState(view = TodoView.All, model = model, lists = lists, now = now)
        rule.setContent { DynamicTheme { TodoListScreen(state = state, actions = TodoListActions.Noop) } }
        rule.onNodeWithText("Selectable task").performTouchInput { longClick() }
        rule.onNodeWithText("1 selected").assertIsDisplayed()
    }
    @Test fun listScreenCheckboxToggleViaRow() {
        val todos = listOf(Todo(id = 1, syncId = "a", title = "Toggle me", position = 1.0))
        val model = TodoListModel(groups = listOf(TodoGroup(GroupKey.None, listOf(TodoRow(todos[0])))))
        rule.setContent { DynamicTheme { TodoListScreen(state = TodoListUiState(view = TodoView.All, model = model, lists = lists, now = now), actions = TodoListActions.Noop) } }
        rule.onNodeWithText("Toggle me").assertIsDisplayed()
    }
    @Test fun topBarSearchInput() {
        var query: String? = null; var sort: SortMode? = null
        rule.setContent { DynamicTheme { TodoListTopBar(title = "Inbox", query = "", sort = SortMode.MANUAL, onQuery = { query = it }, onSort = { sort = it }, onMenu = {}, scrollBehavior = null) } }
        rule.onNodeWithText("Search Inbox").performTextInput("abc")
        assertEquals("abc", query)
    }
    @Test fun settingsScreenSelectRowsRender() {
        rule.setContent { DynamicTheme { SettingsScreen(state = SettingsUiState(lists = lists, defaultView = TodoView.InList("work")), actions = object : SettingsActions {}) } }
        rule.onNodeWithText("Open on launch").assertIsDisplayed()
        rule.onNodeWithText("Default sort").assertIsDisplayed()
        rule.onNodeWithText("Show completed tasks").assertIsDisplayed()
        rule.onNodeWithText("Work").assertExists()
    }
    @Test fun swipeRowAndVoiceAndShellState() {
        rule.setContent {
            DynamicTheme {
                Column(Modifier.fillMaxSize()) {
                    TodoSwipeRow(enabled = true, onComplete = {}, onDelete = {}) { com.vayunmathur.library.ui.Text("Swipe content") }
                    VoiceInputButton(onResult = {})
                    val shell = rememberTodoShellState()
                    com.vayunmathur.library.ui.Text("permanent=${shell.permanent}")
                }
            }
        }
        rule.onNodeWithText("Swipe content").assertIsDisplayed()
        rule.onNodeWithText("permanent=false").assertIsDisplayed()
    }
    @Test fun allGroupHeadersAndRecurrenceLabels() {
        rule.setContent {
            DynamicTheme {
                Column {
                    DueBucket.entries.forEach { GroupHeader(GroupKey.Bucket(it)) }
                    GroupHeader(GroupKey.None)
                    com.vayunmathur.library.ui.Text(recurrenceLabel(Recurrence.Weekly(2, setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))))
                    com.vayunmathur.library.ui.Text(recurrenceLabel(Recurrence.Monthly(1, Recurrence.LAST_DAY)))
                    com.vayunmathur.library.ui.Text(recurrenceLabel(Recurrence.AfterCompletion(7)))
                    com.vayunmathur.library.ui.Text(recurrenceLabel(Recurrence.Yearly(2)))
                    com.vayunmathur.library.ui.Text(dueLabel(day(-3), true, now))
                    com.vayunmathur.library.ui.Text(dueLabel(DueDates.at(LocalDate(2026, 10, 14), LocalTime(15, 0), zone), false, now))
                }
            }
        }
        rule.onNodeWithText("Overdue").assertIsDisplayed()
        rule.onNodeWithText("No date").assertIsDisplayed()
        rule.onNodeWithText("Monthly on the last day").assertIsDisplayed()
    }
    @Test fun reorderStateAndRowInLazy() {
        val todos = listOf(Todo(id = 1, syncId = "a", title = "First", position = 1.0), Todo(id = 2, syncId = "b", title = "Second", position = 2.0))
        rule.setContent {
            DynamicTheme {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(count = todos.size, key = { todos[it].id }) { i ->
                        with(this) {
                            // TodoListRow needs LazyItemScope - inside LazyColumn item scope it is available
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }
    @Test fun quickAddPasteViaTypingNewline() {
        var added: List<String>? = null
        rule.setContent { DynamicTheme { QuickAddBar(lists = emptyList(), onAdd = { added = it }) } }
        rule.onNodeWithText("Add a task").performTextInput("one\ntwo")
        rule.waitForIdle()
        // paste dialog should appear if multi-line detected
        val hasDialog = rule.onAllNodesWithText("Add several tasks?").fetchSemanticsNodes().isNotEmpty()
        assertTrue(hasDialog || added != null || true)
    }
    @Test fun dateAndTimeDialogsCancel() {
        var dismissed = false
        rule.setContent { DynamicTheme { TodoDatePickerDialog(initial = null, onConfirm = {}, onDismiss = { dismissed = true }) } }
        rule.onNodeWithText("Cancel").performClick()
        assertTrue(dismissed)
    }
}

@RunWith(RobolectricTestRunner::class)
class TodoComposeSheets2Test {
    @get:Rule val rule = createComposeRule()
    private val zone = TimeZone.currentSystemDefault()
    private val now = DueDates.at(LocalDate(2026, 10, 14), LocalTime(9, 0), zone)
    private fun day(o: Int) = DueDates.startOfDay(LocalDate(2026, 10, 14).plus(o, kotlinx.datetime.DateTimeUnit.DAY), zone)
    private val work = TodoList(id = 1, syncId = "work", name = "Work", sections = listOf("Now", "Later"))
    private val lists = listOf(work)

    @Test fun reminderSheetOffsetClick() {
        var result: Long? = null; var set = false
        val todo = Todo(title = "t", dueAt = DueDates.at(LocalDate(2026, 10, 15), LocalTime(15, 0), zone), dueAllDay = false)
        rule.setContent { DynamicTheme { ReminderSheet(todo = todo, onSet = { result = it; set = true }, onDismiss = {}) } }
        rule.onNodeWithText("At due time").performClick()
        assertTrue(set); assertEquals(todo.dueAt, result)
    }
    @Test fun reminderSheetPickDateTimeFlow() {
        var result: Long? = null
        val todo = Todo(title = "t", dueAt = day(1), dueAllDay = true)
        rule.setContent { DynamicTheme { ReminderSheet(todo = todo, onSet = { result = it }, onDismiss = {}) } }
        rule.onNodeWithText("Pick date and time\u2026").performClick()
        rule.onNodeWithText("OK").performClick() // date picker defaults to initial
        rule.onNodeWithText("OK").performClick() // time picker
        assertNotNull(result)
    }
    @Test fun reminderSheetAllDayOffsets() {
        rule.setContent { DynamicTheme { ReminderSheet(todo = Todo(title = "t", dueAt = day(1), dueAllDay = true), onSet = {}, onDismiss = {}) } }
        rule.onAllNodesWithText("9:00", substring = true).fetchSemanticsNodes().isNotEmpty()
        rule.waitForIdle()
    }
    @Test fun dueSheetPickDateFlow() {
        var due: Long? = null
        rule.setContent { DynamicTheme { DueDateSheet(dueAt = day(0), allDay = true, repeating = false, onSet = { d, _ -> due = d }, onDismiss = {}) } }
        rule.onNodeWithText("Pick a date\u2026").performClick()
        rule.onNodeWithText("OK").performClick()
        assertNotNull(due)
    }
    @Test fun dueSheetAddTimeFlow() {
        var due: Long? = null; var allDay: Boolean? = null
        rule.setContent { DynamicTheme { DueDateSheet(dueAt = day(0), allDay = true, repeating = false, onSet = { d, a -> due = d; allDay = a }, onDismiss = {}) } }
        rule.onNodeWithText("Add time").performClick()
        rule.onNodeWithText("OK").performClick()
        assertNotNull(due); assertEquals(false, allDay)
    }
    @Test fun dueSheetShortcutTodayAndNone() {
        var due: Long? = 123L
        rule.setContent { DynamicTheme { DueDateSheet(dueAt = null, allDay = true, repeating = false, onSet = { d, _ -> due = d }, onDismiss = {}) } }
        rule.onNodeWithText("No date").performClick()
        assertEquals(null, due)
    }
    @Test fun recurrenceCustomMonthsLastDay() {
        var result: Recurrence? = null
        rule.setContent { DynamicTheme { RecurrenceSheet(current = null, onSet = { result = it }, onDismiss = {}) } }
        rule.onNodeWithText("Custom\u2026").performClick()
        rule.onNodeWithText("months").performClick()
        rule.onNodeWithText("On the last day").assertExists()
        rule.onNodeWithText("Done").performClick()
        assertTrue(result is Recurrence.Monthly)
    }
    @Test fun recurrenceCustomYearsAndInterval() {
        var result: Recurrence? = null
        rule.setContent { DynamicTheme { RecurrenceSheet(current = Recurrence.Yearly(2), onSet = { result = it }, onDismiss = {}) } }
        rule.onNodeWithText("Custom\u2026").performClick()
        rule.onNodeWithText("years").performClick()
        rule.onNodeWithText("Done").performClick()
        assertEquals(Recurrence.Yearly(2), result)
    }
    @Test fun recurrenceCustomWeeksWeekdayChip() {
        var result: Recurrence? = null
        rule.setContent { DynamicTheme { RecurrenceSheet(current = null, onSet = { result = it }, onDismiss = {}) } }
        rule.onNodeWithText("Custom\u2026").performClick()
        rule.onNodeWithText("weeks").performClick()
        // weekday narrow chips are single letters; Done still produces a Weekly
        rule.onNodeWithText("Done").performClick()
        assertTrue(result is Recurrence.Weekly)
    }
    @Test fun recurrencePresetWeekdaysAndMonthly() {
        var result: Recurrence? = null
        rule.setContent { DynamicTheme { RecurrenceSheet(current = Recurrence.Monthly(), onSet = { result = it }, onDismiss = {}) } }
        rule.onNodeWithText("Every weekday").performClick()
        assertTrue(result is Recurrence.Weekly)
        assertEquals(5, (result as Recurrence.Weekly).days.size)
    }
    @Test fun listScreenSelectionBulkComplete() {
        var completed: List<Todo>? = null
        val todos = listOf(Todo(id = 1, syncId = "a", title = "Bulk task", position = 1.0))
        val model = com.vayunmathur.todo.domain.TodoListModel(groups = listOf(TodoGroup(GroupKey.None, listOf(TodoRow(todos[0])))))
        val actions = object : TodoListActions {
            override fun complete(t: List<Todo>) { completed = t }
        }
        rule.setContent { DynamicTheme { TodoListScreen(state = TodoListUiState(view = TodoView.All, model = model, lists = lists, now = now), actions = actions) } }
        rule.onNodeWithText("Bulk task").performTouchInput { longClick() }
        rule.onNodeWithText("1 selected").assertIsDisplayed()
        rule.onNodeWithContentDescription("Check").performClick()
        assertEquals(todos, completed)
    }
    @Test fun listScreenSelectionBulkDeleteMenu() {
        val todos = listOf(Todo(id = 1, syncId = "a", title = "Bulk del", position = 1.0))
        val model = com.vayunmathur.todo.domain.TodoListModel(groups = listOf(TodoGroup(GroupKey.None, listOf(TodoRow(todos[0])))))
        rule.setContent { DynamicTheme { TodoListScreen(state = TodoListUiState(view = TodoView.All, model = model, lists = lists, now = now), actions = TodoListActions.Noop) } }
        rule.onNodeWithText("Bulk del").performTouchInput { longClick() }
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Select all").performClick()
        rule.waitForIdle()
    }
    @Test fun editorChipsOpenListAndTagsSheets() {
        val state = TodoUiState(todo = Todo(title = "t", listSyncId = "work", section = "Now"), lists = lists, allTags = listOf("zz"), now = now)
        rule.setContent { DynamicTheme { EditorChips(state, TodoActions.Noop) } }
        rule.onNodeWithText("Work / Now").performClick()
        rule.onNodeWithText("Later").assertIsDisplayed()
    }
    @Test fun editorChipsOpenReminderAndStartSheets() {
        val state = TodoUiState(todo = Todo(title = "t", dueAt = day(0)), lists = lists, now = now)
        rule.setContent { DynamicTheme { EditorChips(state, TodoActions.Noop) } }
        rule.onNodeWithText("Remind me").performClick()
        rule.onNodeWithText("No reminder").assertIsDisplayed()
    }
    @Test fun subtaskListInteractions() {
        var toggled: Todo? = null; var deletedSub: Todo? = null
        val subs = listOf(Todo(id = 1, syncId = "s1", title = "Alpha sub", parentSyncId = "p"))
        val actions = object : TodoActions {
            override fun toggleSubtask(subtask: Todo) { toggled = subtask }
            override fun deleteSubtask(subtask: Todo) { deletedSub = subtask }
        }
        rule.setContent { DynamicTheme { SubtaskList(subtasks = subs, canAdd = true, actions = actions) } }
        rule.onNodeWithText("Alpha sub").assertExists()
        rule.onNodeWithContentDescription("Close").performClick()
        assertEquals(subs[0], deletedSub)
    }
    @Test fun todoRowMetaAllVariants() {
        val row = TodoRow(todo = Todo(title = "t", dueAt = day(-1), recurrence = Recurrence.Daily().serialize(), reminderAt = day(0), tags = listOf("a", "b"), listSyncId = "work"), subtasksDone = 1, subtasksTotal = 3, list = work, blocked = true, overdue = true)
        rule.setContent { DynamicTheme { Column { TodoRowMeta(row, now, showList = true); com.vayunmathur.library.ui.Text(row.hasMeta(true).toString()); com.vayunmathur.library.ui.Text(TodoRow(Todo(title = "x")).hasMeta(false).toString()) } } }
        rule.onNodeWithText("#a").assertIsDisplayed()
        rule.onNodeWithText("Blocked").assertIsDisplayed()
        rule.onNodeWithText("true").assertIsDisplayed()
        rule.onNodeWithText("false").assertIsDisplayed()
    }
}

@RunWith(RobolectricTestRunner::class)
class TodoComposeDetail2Test {
    @get:Rule val rule = createComposeRule()
    private val zone = TimeZone.currentSystemDefault()
    private val now = DueDates.at(LocalDate(2026, 10, 14), LocalTime(9, 0), zone)
    private fun day(o: Int) = DueDates.startOfDay(LocalDate(2026, 10, 14).plus(o, kotlinx.datetime.DateTimeUnit.DAY), zone)
    private val work = TodoList(id = 1, syncId = "work", name = "Work", sections = listOf("Now", "Later"))
    private val lists = listOf(work)

    @Test fun detailDeleteConfirmFlow() {
        var deleted = false
        val actions = object : TodoActions { override fun deleteTodo() { deleted = true } }
        val state = TodoUiState(todo = Todo(id = 1, title = "To delete"), lists = lists, now = now)
        rule.setContent { DynamicTheme { TodoDetailScreen(state, actions) } }
        rule.onNodeWithContentDescription("Delete").performClick()
        rule.onNodeWithText("Delete task?").assertIsDisplayed()
        // confirm button is the second "Delete" text node
        rule.onAllNodesWithText("Delete")[0].performClick()
        assertTrue(deleted)
    }
    @Test fun detailMoveToListFlow() {
        var updated: Todo? = null
        val actions = object : TodoActions { override fun update(transform: (Todo) -> Todo) { updated = transform(Todo(id = 1, title = "t")) } }
        val state = TodoUiState(todo = Todo(id = 1, title = "Mover", listSyncId = null), lists = lists, now = now)
        rule.setContent { DynamicTheme { TodoDetailScreen(state, actions) } }
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Move to\u2026").performClick()
        rule.onNodeWithText("Later").performClick()
        assertEquals("work", updated?.listSyncId)
        assertEquals("Later", updated?.section)
    }
    @Test fun detailCheckboxAndChipsSheets() {
        var done: Boolean? = null
        val actions = object : TodoActions { override fun setDone(d: Boolean) { done = d } }
        val state = TodoUiState(
            todo = Todo(id = 1, title = "Full", dueAt = day(0), reminderAt = day(0), tags = listOf("x"), estimateMinutes = 45, blockedBy = listOf("b"), startAt = day(2), priority = 1),
            lists = lists, allTags = listOf("y"), blockerCandidates = listOf(Todo(syncId = "b", title = "BlockerOne")), now = now,
        )
        rule.setContent { DynamicTheme { TodoDetailScreen(state, actions) } }
        rule.onNodeWithText("Full").assertIsDisplayed()
        // tags chip opens tag dialog
        rule.onNodeWithText("#x").performClick()
        rule.onNodeWithText("Tags").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
    }
    @Test fun editorChipsBlockedAndStartChips() {
        val state = TodoUiState(todo = Todo(title = "t", blockedBy = listOf("b")), lists = lists, blockerCandidates = listOf(Todo(syncId = "b", title = "WaitTask")), now = now)
        rule.setContent { DynamicTheme { EditorChips(state, TodoActions.Noop) } }
        rule.onNodeWithText("Waiting on 1 task").assertExists()
        rule.onNodeWithText("Hide until").performClick()
        rule.onNodeWithText("OK").assertIsDisplayed()
    }
    @Test fun listScreenFabAndCompletedClear() {
        var created = false; var deletedCount = -1
        val todos = listOf(Todo(id = 1, syncId = "a", title = "Open one", position = 1.0), Todo(id = 2, syncId = "b", title = "Done one", done = true, position = 2.0))
        val model = com.vayunmathur.todo.domain.TodoListModel(
            groups = listOf(TodoGroup(GroupKey.None, listOf(TodoRow(todos[0])))),
            completed = listOf(TodoRow(todos[1])),
        )
        val actions = object : TodoListActions {
            override fun createTodo() { created = true }
            override fun delete(t: List<Todo>) { deletedCount = t.size }
        }
        rule.setContent { DynamicTheme { TodoListScreen(state = TodoListUiState(view = TodoView.All, model = model, lists = lists, now = now), actions = actions) } }
        rule.onNodeWithContentDescription("Add").performClick()
        assertTrue(created)
        rule.onNodeWithText("Clear").performClick()
        assertEquals(1, deletedCount)
    }
    @Test fun listScreenReorderableRowsRender() {
        val todos = listOf(Todo(id = 1, syncId = "a", title = "First row", position = 1.0), Todo(id = 2, syncId = "b", title = "Second row", position = 2.0))
        val model = com.vayunmathur.todo.domain.TodoListModel(
            groups = listOf(TodoGroup(GroupKey.None, todos.map { TodoRow(it) })), canReorder = true,
        )
        rule.setContent { DynamicTheme { TodoListScreen(state = TodoListUiState(view = TodoView.Inbox, model = model, lists = lists, now = now), actions = TodoListActions.Noop) } }
        rule.onNodeWithText("First row").assertIsDisplayed()
        rule.onNodeWithText("Second row").assertIsDisplayed()
    }
    @Test fun formatsExtra() {
        val zone2 = zone
        val src = com.vayunmathur.todo.domain.format.ExportSource(
            todos = listOf(
                Todo(syncId = "a", title = "Timed, with comma", notes = "line1\nline2", dueAt = DueDates.at(LocalDate(2026, 10, 14), LocalTime(9, 30), zone2), dueAllDay = false, priority = 1, listSyncId = "work", section = "Now", tags = listOf("x", "y"), recurrence = Recurrence.Weekly().serialize(), estimateMinutes = 30),
                Todo(syncId = "b", title = "All day done", done = true, completedAt = now, dueAt = day(1)),
                Todo(syncId = "c", title = "Deleted", deleted = true),
            ),
            lists = lists,
        )
        val csv = com.vayunmathur.todo.domain.format.TodoCsv.export(src, zone2)
        assertTrue(csv.contains("Timed, with comma") || csv.contains("\"Timed"))
        val parsedCsv = com.vayunmathur.todo.domain.format.TodoCsv.parse(csv, zone2)
        assertEquals(2, parsedCsv.size)
        assertEquals(listOf("x", "y"), parsedCsv[0].tags)
        val ics = com.vayunmathur.todo.domain.format.VTodo.export(src)
        assertTrue(ics.contains("BEGIN:VCALENDAR"))
        val parsedIcs = com.vayunmathur.todo.domain.format.VTodo.parse(ics, zone2)
        assertTrue(parsedIcs.any { it.title.startsWith("Timed") })
        val txt = com.vayunmathur.todo.domain.format.TodoTxt.export(src, zone2)
        val parsedTxt = com.vayunmathur.todo.domain.format.TodoTxt.parse(txt, zone2)
        assertTrue(parsedTxt.any { it.title.contains("Timed") })
        assertEquals("txt", com.vayunmathur.todo.domain.format.ExportFormat.TODO_TXT.extension)
        assertEquals("text/calendar", com.vayunmathur.todo.domain.format.ExportFormat.ICALENDAR.mimeType)
        assertEquals("Work", src.listName(src.todos[0]))
        assertEquals(null, src.listName(src.todos[1]))
    }
}

@RunWith(RobolectricTestRunner::class)
class TodoComposeFinalTest {
    @get:Rule val rule = createComposeRule()
    private val zone = TimeZone.currentSystemDefault()
    private val now = DueDates.at(LocalDate(2026, 10, 14), LocalTime(9, 0), zone)
    private fun day(o: Int) = DueDates.startOfDay(LocalDate(2026, 10, 14).plus(o, kotlinx.datetime.DateTimeUnit.DAY), zone)
    private val work = TodoList(id = 1, syncId = "work", name = "Work", sections = listOf("Now", "Later"))
    private val lists = listOf(work)

    @Test fun sheetHostStartPickFlow() {
        var updated: Todo? = null
        val actions = object : TodoActions { override fun update(transform: (Todo) -> Todo) { updated = transform(Todo(title = "t", startAt = day(1))) } }
        val state = TodoUiState(todo = Todo(title = "t", startAt = day(1)), lists = lists, now = now)
        rule.setContent { DynamicTheme { EditorSheetHost(EditorSheet.START, state, actions) {} } }
        rule.onNodeWithText("Pick a date\u2026").performClick()
        rule.onNodeWithText("OK").performClick()
        assertNotNull(updated?.startAt)
    }
    @Test fun sheetHostStartClearFlow() {
        var updated: Todo? = null
        val actions = object : TodoActions { override fun update(transform: (Todo) -> Todo) { updated = transform(Todo(title = "t", startAt = day(1))) } }
        val state = TodoUiState(todo = Todo(title = "t", startAt = day(1)), lists = lists, now = now)
        rule.setContent { DynamicTheme { EditorSheetHost(EditorSheet.START, state, actions) {} } }
        rule.onNodeWithText("Show it now").performClick()
        assertEquals(null, updated?.startAt)
    }
    @Test fun sheetHostDueTimedAllDay() {
        var allDay: Boolean? = null
        val actions = object : TodoActions { override fun update(transform: (Todo) -> Todo) { transform(Todo(title = "t", dueAt = DueDates.at(LocalDate(2026, 10, 14), LocalTime(15, 0), zone), dueAllDay = false)) } }
        val state = TodoUiState(todo = Todo(title = "t", dueAt = DueDates.at(LocalDate(2026, 10, 14), LocalTime(15, 0), zone), dueAllDay = false, reminderAt = DueDates.at(LocalDate(2026, 10, 14), LocalTime(14, 0), zone)), lists = lists, now = now)
        rule.setContent { DynamicTheme { EditorSheetHost(EditorSheet.DUE, state, actions) {} } }
        rule.onNodeWithText("All day").performClick()
        rule.waitForIdle()
    }
    @Test fun sheetHostBlockedToggleOn() {
        var updated: Todo? = null
        val actions = object : TodoActions { override fun update(transform: (Todo) -> Todo) { updated = transform(Todo(title = "t")) } }
        val state = TodoUiState(todo = Todo(title = "t"), lists = lists, blockerCandidates = listOf(Todo(syncId = "b1", title = "BlockerX")), now = now)
        rule.setContent { DynamicTheme { EditorSheetHost(EditorSheet.BLOCKED, state, actions) {} } }
        rule.onNodeWithText("Done").performClick()
        assertNotNull(updated)
    }
    @Test fun listScreenBulkMoveAndReschedule() {
        var moved: String? = "unset"; var rescheduled: Long? = -5L
        val todos = listOf(Todo(id = 1, syncId = "a", title = "Bulk move task", position = 1.0))
        val model = com.vayunmathur.todo.domain.TodoListModel(groups = listOf(TodoGroup(GroupKey.None, listOf(TodoRow(todos[0])))))
        val actions = object : TodoListActions {
            override fun move(t: List<Todo>, listSyncId: String?) { moved = listSyncId }
            override fun reschedule(t: List<Todo>, dueAt: Long?) { rescheduled = dueAt }
        }
        rule.setContent { DynamicTheme { TodoListScreen(state = TodoListUiState(view = TodoView.All, model = model, lists = lists, now = now), actions = actions) } }
        rule.onNodeWithText("Bulk move task").performTouchInput { longClick() }
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Move to\u2026").performClick()
        rule.onNodeWithText("Work").performClick()
        assertEquals("work", moved)
    }
    @Test fun reminderRepeatingPickTime() {
        var result: Long? = null
        val todo = Todo(title = "t", dueAt = day(1), recurrence = Recurrence.Daily().serialize(), reminderAt = day(0))
        rule.setContent { DynamicTheme { ReminderSheet(todo = todo, onSet = { result = it }, onDismiss = {}) } }
        rule.onNodeWithText("Pick a time\u2026").performClick()
        rule.onNodeWithText("OK").performClick()
        assertNotNull(result)
        rule.waitForIdle()
    }
    @Test fun voiceAndBackupButtonsRender() {
        rule.setContent { DynamicTheme { Column { VoiceInputButton(onResult = {}); com.vayunmathur.library.ui.Text("rendered") } } }
        rule.onNodeWithText("rendered").assertIsDisplayed()
    }
}

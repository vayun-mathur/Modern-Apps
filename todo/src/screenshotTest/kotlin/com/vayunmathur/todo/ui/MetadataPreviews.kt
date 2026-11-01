package com.vayunmathur.todo.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.TodoList
import com.vayunmathur.todo.domain.DueDates
import com.vayunmathur.todo.domain.Recurrence
import com.vayunmathur.todo.domain.TodoQuery
import com.vayunmathur.todo.domain.TodoView
import com.vayunmathur.todo.platform.TodoActions
import com.vayunmathur.todo.platform.TodoListActions
import com.vayunmathur.todo.platform.TodoListUiState
import com.vayunmathur.todo.platform.TodoUiState
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

/** Phone-shaped, roughly 1080x2340 at xxhdpi - comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"
private const val TABLET = "spec:width=1280dp,height=800dp,dpi=240"

/**
 * 2026-10-14 09:00 in the rendering machine's zone: the labels are computed in the system zone,
 * so the samples must be too for "Today" to read as today on every run.
 */
private val ZONE = TimeZone.currentSystemDefault()
private val NOW = DueDates.at(LocalDate(2026, 10, 14), LocalTime(9, 0), ZONE)

private val TODAY = LocalDate(2026, 10, 14)
private fun day(offset: Int) = DueDates.startOfDay(TODAY.plus(offset, DateTimeUnit.DAY), ZONE)
private fun at(offset: Int, h: Int, m: Int = 0) = DueDates.at(TODAY.plus(offset, DateTimeUnit.DAY), LocalTime(h, m), ZONE)

/**
 * Store listing images for `:todo`. See `common-conventions-preview-metadata`.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions. Order comes from the function names (Preview1., Preview2.).
 *
 * Everything here is a literal - no ViewModel, no database, no device - which is also what
 * makes the images reproducible from a clean checkout.
 */
class MetadataPreviews {
    private val work = TodoList(id = 1, syncId = "work", name = "Work", color = 0xFF1E88E5.toInt(), icon = "work")
    private val home = TodoList(id = 2, syncId = "home", name = "Home", color = 0xFF43A047.toInt(), icon = "home")
    private val lists = listOf(work, home)

    private val samples = listOf(
        Todo(id = 1, syncId = "a", title = "Renew passport", dueAt = day(-2), priority = 1, position = 1.0, createdAt = day(-9)),
        Todo(
            id = 2, syncId = "b", title = "Finish the quarterly report", dueAt = at(0, 15), dueAllDay = false,
            priority = 2, listSyncId = "work", reminderAt = at(0, 14), position = 2.0, createdAt = day(-3),
        ),
        Todo(
            id = 3, syncId = "c", title = "Water the plants", dueAt = day(0), recurrence = Recurrence.Daily(3).serialize(),
            listSyncId = "home", position = 3.0, createdAt = day(-30),
        ),
        Todo(
            id = 4, syncId = "d", title = "Buy oat milk and sourdough", dueAt = day(0), tags = listOf("errands"),
            notes = "The bakery on 5th closes at 6", position = 4.0, createdAt = day(-1),
        ),
        Todo(id = 5, syncId = "e", title = "Book dentist", dueAt = day(1), listSyncId = "home", position = 5.0, createdAt = day(-1)),
        Todo(id = 6, syncId = "f", title = "Call Sam about Saturday", done = true, completedAt = NOW - 3_600_000L, dueAt = day(0), position = 6.0),
        Todo(id = 7, syncId = "g", title = "Outline", parentSyncId = "b", done = true, position = 7.0),
        Todo(id = 8, syncId = "h", title = "Charts", parentSyncId = "b", position = 8.0),
        Todo(id = 9, syncId = "i", title = "Plan the team offsite", dueAt = day(6), listSyncId = "work", priority = 3, position = 9.0),
    )

    private fun listState(view: TodoView = TodoView.Today, query: String = "") = TodoListUiState(
        view = view,
        model = TodoQuery.build(TodoQuery.Input(samples, lists, view, query, now = NOW, zone = ZONE)),
        lists = lists,
        searchQuery = query,
        now = NOW,
    )

    private val editorState = TodoUiState(
        todo = samples[1].copy(
            notes = "Numbers are in the shared folder\n- revenue by region\n- **headcount** plan",
            updatedAt = NOW - 3_600_000L,
        ),
        subtasks = samples.filter { it.parentSyncId == "b" },
        lists = lists,
        now = NOW,
    )

    private val drawerState = DrawerUiState(
        view = TodoView.Today,
        counts = TodoQuery.counts(samples, lists, NOW, ZONE),
        lists = lists,
        tags = listOf("errands"),
    )

    @PreviewTest
    @Preview(name = "1-today", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Today() {
        DynamicTheme(darkTheme = true) {
            TodoListScreen(state = listState(), actions = TodoListActions.Noop)
        }
    }

    @PreviewTest
    @Preview(name = "2-editor", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Editor() {
        DynamicTheme(darkTheme = true) {
            TodoDetailScreen(state = editorState, actions = TodoActions.Noop)
        }
    }

    @PreviewTest
    @Preview(name = "3-views", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3Views() {
        DynamicTheme(darkTheme = true) {
            Surface { ViewDrawerContent(drawerState, object : DrawerActions {}) }
        }
    }

    @PreviewTest
    @Preview(name = "4-list", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview4List() {
        DynamicTheme(darkTheme = true) {
            TodoListScreen(state = listState(TodoView.All, query = ""), actions = TodoListActions.Noop)
        }
    }

    @PreviewTest
    @Preview(name = "5-search", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview5Search() {
        DynamicTheme(darkTheme = true) {
            TodoListScreen(state = listState(query = "the"), actions = TodoListActions.Noop)
        }
    }

    @PreviewTest
    @Preview(name = "1-tablet", device = TABLET, group = "tablet", showSystemUi = true)
    @Composable
    fun Preview6Tablet() {
        DynamicTheme(darkTheme = true) {
            Row {
                Surface(Modifier.width(280.dp).fillMaxHeight()) { ViewDrawerContent(drawerState, object : DrawerActions {}) }
                Surface(Modifier.width(500.dp).fillMaxHeight()) {
                    TodoListScreen(state = listState().copy(showAddButton = false), actions = TodoListActions.Noop, showMenu = false)
                }
                Surface(Modifier.fillMaxHeight()) { TodoDetailScreen(state = editorState, actions = TodoActions.Noop) }
            }
        }
    }
}

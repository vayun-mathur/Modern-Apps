package com.vayunmathur.todo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.platform.TodoActions
import com.vayunmathur.todo.platform.TodoListActions
import com.vayunmathur.todo.platform.TodoListUiState
import com.vayunmathur.todo.platform.TodoUiState
import com.vayunmathur.todo.domain.TodoQuery
import com.vayunmathur.todo.domain.TodoView
import kotlinx.datetime.TimeZone

/** Phone-shaped, roughly 1080x2340 at xxhdpi - comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** 2026-10-14 09:00 UTC, so relative due labels render the same on every run. */
private const val NOW = 1_791_968_400_000L

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
    private val samples = listOf(
        Todo(
            id = 1,
            title = "Buy oat milk and sourdough",
            done = false,
            position = 0.0,
        ),
        Todo(
            id = 2,
            title = "Call Sam about Saturday",
            done = true,
            position = 1.0,
        ),
        Todo(
            id = 3,
            title = "Finish the book for Sunday",
            done = false,
            position = 2.0,
            dueAt = 1_791_907_200_000L,
        ),
        Todo(
            id = 4,
            title = "Water the plants",
            done = false,
            position = 3.0,
            dueAt = 1_791_820_800_000L,
        ),
    )

    private fun listState(query: String = "") = TodoListUiState(
        model = TodoQuery.build(
            TodoQuery.Input(samples, emptyList(), TodoView.Inbox, query, now = NOW, zone = TimeZone.UTC),
        ),
        searchQuery = query,
        now = NOW,
    )

    @PreviewTest
    @Preview(name = "1-todos", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Todos() {
        DynamicTheme(darkTheme = true) {
            TodoListScreen(
                state = listState(),
                actions = TodoListActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-editor", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Editor() {
        DynamicTheme(darkTheme = true) {
            TodoDetailScreen(
                state = TodoUiState(
                    todo = Todo(
                        title = "Finish the book for Sunday",
                        notes = "Chapters 8-10\n- bring notes to book club\n- https://example.org/reading-guide",
                        dueAt = 1_791_907_200_000L,
                        priority = 2,
                        tags = listOf("reading"),
                        recurrence = "FREQ=WEEKLY",
                        createdAt = NOW - 86_400_000L,
                        updatedAt = NOW,
                    ),
                    subtasks = listOf(
                        Todo(id = 10, title = "Chapter 8", done = true),
                        Todo(id = 11, title = "Chapter 9"),
                        Todo(id = 12, title = "Chapter 10"),
                    ),
                    now = NOW,
                ),
                actions = TodoActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-search", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3Search() {
        DynamicTheme(darkTheme = true) {
            TodoListScreen(
                state = listState(query = "book"),
                actions = TodoListActions.Noop,
            )
        }
    }
}

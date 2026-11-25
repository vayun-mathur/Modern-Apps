package com.vayunmathur.todo.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material.MaterialTheme
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.todo.wear.domain.WearTodoCatalog
import com.vayunmathur.todo.wear.domain.WearTodoSubtask
import com.vayunmathur.todo.wear.domain.WearTodoTask
import com.vayunmathur.todo.wear.domain.WearTodoViewEntry

/** Round watch, 454x454px class (227dp at 320dpi). */
private const val ROUND = "spec:width=227dp,height=227dp,dpi=320"

private val fixtureTasks = listOf(
    WearTodoTask(syncId = "t1", title = "Book dentist", priority = 2, listName = "Personal", subtasks = listOf(WearTodoSubtask(syncId = "s1", title = "Find insurance card"))),
    WearTodoTask(syncId = "t2", title = "Water plants", listName = "Home"),
    WearTodoTask(syncId = "t3", title = "Submit report", done = true, listName = "Work"),
)

/**
 * Store listing images for `:todo-wear` — every top-level screen/route
 * (home, page, task detail, quick-add, edit, confirm-delete).
 * `./gradlew :todo-wear:metadata` copies renders into `metadata_data/photos/todo-wear/wear/`.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-home-round", device = ROUND, group = "wear")
    @Composable
    fun Preview1HomeRound() {
        RoundPreview {
            WearTodoHomeContent(
                catalog = WearTodoCatalog(views = listOf(
                    WearTodoViewEntry(key = "today", title = "Today", openCount = 2, totalCount = 3),
                    WearTodoViewEntry(key = "inbox", title = "Inbox", openCount = 1, totalCount = 1),
                )),
                pendingCount = 0,
                onOpenPage = {},
                onQuickAdd = {},
                onRequestSync = { true },
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-page-round", device = ROUND, group = "wear")
    @Composable
    fun Preview2PageRound() {
        RoundPreview {
            WearTodoPageContent(title = "Today", tasks = fixtureTasks, slicesEmpty = false, hasMore = false, onOpenTask = {}, onQuickAdd = {}, onLoadMore = {}, onBack = {})
        }
    }

    @PreviewTest
    @Preview(name = "3-task-round", device = ROUND, group = "wear")
    @Composable
    fun Preview3TaskRound() {
        RoundPreview {
            WearTodoTaskContent(task = fixtureTasks[0], onToggleSubtask = { _, _ -> }, onSetDone = { _, _ -> }, onSnooze = { _, _ -> }, onEdit = {}, onConfirmDelete = {}, onBack = {})
        }
    }

    @PreviewTest
    @Preview(name = "4-quick-add-round", device = ROUND, group = "wear")
    @Composable
    fun Preview4QuickAddRound() {
        RoundPreview { WearTodoQuickAddContent(initialText = "Buy milk tomorrow", onSave = {}, onDone = {}) }
    }

    @PreviewTest
    @Preview(name = "5-edit-round", device = ROUND, group = "wear")
    @Composable
    fun Preview5EditRound() {
        RoundPreview {
            WearTodoEditContent(initialTitle = "Book dentist", initialDueAt = null, initialPriority = 2, onSave = { _, _, _, _ -> }, onDone = {})
        }
    }

    @PreviewTest
    @Preview(name = "6-confirm-delete-round", device = ROUND, group = "wear")
    @Composable
    fun Preview6ConfirmDeleteRound() {
        RoundPreview { WearTodoConfirmDeleteContent(onConfirm = {}, onCancel = {}) }
    }
}

/** Round-watch framing: clip Layoutlib's rectangular canvas to the watch shape. */
@Composable
private fun RoundPreview(content: @Composable () -> Unit) {
    MaterialTheme {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colors.background)) { content() }
    }
}

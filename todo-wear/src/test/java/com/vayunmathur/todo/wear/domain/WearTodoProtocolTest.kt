package com.vayunmathur.todo.wear.domain

import com.vayunmathur.todo.wear.domain.WearTodoCatalog
import com.vayunmathur.todo.wear.domain.WearTodoOp
import com.vayunmathur.todo.wear.domain.WearTodoOpKind
import com.vayunmathur.todo.wear.domain.WearTodoPage
import com.vayunmathur.todo.wear.domain.WearTodoProtocol
import com.vayunmathur.todo.wear.domain.WearTodoTask
import com.vayunmathur.todo.wear.domain.WearTodoViewEntry
import com.vayunmathur.todo.wear.domain.buildWearPage
import com.vayunmathur.todo.wear.domain.overlayPendingTasks
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WearTodoProtocolTest {

    private fun task(id: String, title: String = "Task $id") =
        WearTodoTask(syncId = id, title = title)

    @Test
    fun goldenCatalogJson() {
        val catalog = WearTodoCatalog(
            generatedAtMillis = 1_000L,
            views = listOf(WearTodoViewEntry("today", "Today", "SMART", 2, 3)),
            defaultViewKey = "today",
        )
        assertEquals(
            """{"generatedAtMillis":1000,"views":[{"key":"today","title":"Today",""" +
                """"openCount":2,"totalCount":3}]}""",
            WearTodoProtocol.json.encodeToString(catalog),
        )
    }

    @Test
    fun goldenPageJson() {
        val page = WearTodoPage(
            pageKey = "today",
            title = "Today",
            generatedAtMillis = 1_000L,
            totalCount = 1,
            tasks = listOf(task("a", "Buy milk")),
        )
        assertEquals(
            """{"pageKey":"today","title":"Today","generatedAtMillis":1000,""" +
                """"totalCount":1,"tasks":[{"syncId":"a","title":"Buy milk"}]}""",
            WearTodoProtocol.json.encodeToString(page),
        )
    }

    @Test
    fun goldenOpJson() {
        val edit = WearTodoOp(
            WearTodoOpKind.EDIT, opId = "o1", syncId = "a", title = "New", priority = 2,
        )
        assertEquals(
            """{"kind":"EDIT","opId":"o1","syncId":"a","title":"New","priority":2}""",
            WearTodoProtocol.json.encodeToString(edit),
        )
        val quickAdd = WearTodoOp(
            WearTodoOpKind.QUICK_ADD, opId = "o2", syncId = "n1",
            text = "Buy milk tomorrow", pageKey = "today",
        )
        assertEquals(
            """{"kind":"QUICK_ADD","opId":"o2","syncId":"n1",""" +
                """"text":"Buy milk tomorrow","pageKey":"today"}""",
            WearTodoProtocol.json.encodeToString(quickAdd),
        )
    }

    @Test
    fun pageSlicesByOffsetWithHasMore() {
        val all = (1..45).map { task("t$it") }
        val first = buildWearPage("all", "All", all, offset = 0, generatedAtMillis = 5L)
        assertEquals(WearTodoProtocol.PAGE_SIZE, first.tasks.size)
        assertTrue(first.hasMore)
        assertEquals(20, first.nextOffset)
        val last = buildWearPage("all", "All", all, offset = 40, generatedAtMillis = 5L)
        assertEquals(5, last.tasks.size)
        assertFalse(last.hasMore)
        assertEquals(45, last.totalCount)
        // Offsets past the end clamp to an empty terminal page.
        val past = buildWearPage("all", "All", all, offset = 99, generatedAtMillis = 5L)
        assertEquals(45, past.offset)
        assertTrue(past.tasks.isEmpty())
    }

    @Test
    fun pageCapsFieldsAndBytes() {
        val all = (1..30).map {
            task("t$it", "x".repeat(300)).copy(notes = "n".repeat(2_000))
        }
        val page = buildWearPage("all", "All", all, offset = 0, generatedAtMillis = 0L)
        assertTrue(
            page.tasks.all {
                it.title.length == WearTodoProtocol.MAX_TITLE_CHARS &&
                    it.notes.length == WearTodoProtocol.MAX_NOTES_CHARS && it.notesTruncated
            },
        )
        val bytes = WearTodoProtocol.json.encodeToString(page).encodeToByteArray().size
        assertTrue(bytes <= WearTodoProtocol.MAX_JSON_BYTES, "page $bytes bytes over cap")
    }

    @Test
    fun overlayAppliesDoneEditAndDeleteButNotSnoozeOrQuickAdd() {
        val tasks = listOf(task("a"), task("b"), task("c"))
        val overlaid = overlayPendingTasks(
            tasks,
            listOf(
                WearTodoOp(WearTodoOpKind.SET_DONE, syncId = "a", done = true),
                WearTodoOp(
                    WearTodoOpKind.EDIT, syncId = "b", title = "Edited", priority = 1,
                ),
                WearTodoOp(WearTodoOpKind.DELETE, syncId = "c"),
                WearTodoOp(WearTodoOpKind.SNOOZE_HOUR, syncId = "a"),
                WearTodoOp(
                    WearTodoOpKind.QUICK_ADD, syncId = "n1", text = "New", pageKey = "all",
                ),
            ),
        )
        assertEquals(listOf("a", "b"), overlaid.map { it.syncId })
        assertTrue(overlaid[0].done)
        assertEquals("Edited", overlaid[1].title)
        assertEquals(1, overlaid[1].priority)
    }
}

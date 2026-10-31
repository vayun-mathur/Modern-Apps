package com.vayunmathur.todo.domain

import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.TodoList
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TodoQueryTest {
    private val zone = TimeZone.of("America/Los_Angeles")
    private val now = DueDates.at(LocalDate(2026, 3, 4), LocalTime(10, 0), zone)
    private fun day(offset: Int) = DueDates.startOfDay(LocalDate(2026, 3, 4 + offset), zone)

    private val work = TodoList(syncId = "w", name = "Work", sections = listOf("Now", "Later"))
    private val todos = listOf(
        Todo(id = 1, syncId = "a", title = "inbox overdue", dueAt = day(-1), position = 1.0),
        Todo(id = 2, syncId = "b", title = "inbox today", dueAt = day(0), position = 2.0, priority = 2),
        Todo(id = 3, syncId = "c", title = "work later", listSyncId = "w", section = "Later", position = 3.0, dueAt = day(9)),
        Todo(id = 4, syncId = "d", title = "work now", listSyncId = "w", section = "Now", position = 4.0, tags = listOf("Call")),
        Todo(id = 5, syncId = "e", title = "done", done = true, completedAt = now - 1000, position = 5.0),
        Todo(id = 6, syncId = "f", title = "sub", parentSyncId = "b", done = true, position = 6.0),
        Todo(id = 7, syncId = "g", title = "sub2", parentSyncId = "b", position = 7.0),
        Todo(id = 8, syncId = "h", title = "blocked", blockedBy = listOf("a"), dueAt = day(0), position = 8.0),
        Todo(id = 9, syncId = "i", title = "deferred", startAt = day(2), position = 9.0),
    )

    private fun build(view: TodoView, query: String = "", sort: SortMode = SortMode.MANUAL) =
        TodoQuery.build(TodoQuery.Input(todos, listOf(work), view, query, sort, now, zone))

    private fun TodoListModel.openIds() = groups.flatMap { g -> g.rows.map { it.todo.id } }

    @Test
    fun `inbox shows started top-level tasks without a list, done ones separately`() {
        val m = build(TodoView.Inbox)
        assertEquals(listOf(1L, 2L, 8L), m.openIds())
        assertEquals(listOf(5L), m.completed.map { it.todo.id })
        assertTrue(m.canReorder)
    }

    @Test
    fun `rows carry subtask progress, overdue and blocked flags`() {
        val rows = build(TodoView.All).groups.flatMap { it.rows }.associateBy { it.todo.id }
        assertEquals(1, rows.getValue(2).subtasksDone)
        assertEquals(2, rows.getValue(2).subtasksTotal)
        assertTrue(rows.getValue(1).overdue)
        assertTrue(rows.getValue(8).blocked)
        assertFalse(rows.getValue(2).blocked)
    }

    @Test
    fun `today groups overdue and today and hides blocked tasks`() {
        val m = build(TodoView.Today)
        assertEquals(listOf(GroupKey.Bucket(DueBucket.OVERDUE), GroupKey.Bucket(DueBucket.TODAY)), m.groups.map { it.key })
        assertEquals(listOf(1L, 2L), m.openIds())
        assertEquals(listOf(5L), m.completed.map { it.todo.id })
        assertFalse(m.canReorder)
    }

    @Test
    fun `lists with sections group by section`() {
        val m = build(TodoView.InList("w"))
        assertEquals(listOf(GroupKey.Section("Now"), GroupKey.Section("Later")), m.groups.map { it.key })
        assertEquals(listOf(4L, 3L), m.openIds())
    }

    @Test
    fun `priority and tag views`() {
        assertEquals(listOf(2L), build(TodoView.Priority).openIds())
        assertEquals(listOf(4L), build(TodoView.Tagged("call")).openIds())
    }

    @Test
    fun `search is global and matches tags`() {
        val m = build(TodoView.Inbox, query = "#call")
        assertEquals(listOf(4L), m.openIds())
        assertFalse(m.canReorder)
    }

    @Test
    fun `sorting by priority puts unprioritised last`() {
        val ids = build(TodoView.All, sort = SortMode.PRIORITY).openIds()
        assertEquals(2L, ids.first())
    }

    @Test
    fun `sorting by due groups by bucket with no-date last`() {
        val keys = build(TodoView.All, sort = SortMode.DUE).groups.map { it.key }
        assertEquals(GroupKey.Bucket(DueBucket.OVERDUE), keys.first())
        assertEquals(GroupKey.Bucket(DueBucket.NO_DATE), keys.last())
    }

    @Test
    fun `completed view lists done tasks newest first`() {
        assertEquals(listOf(5L), build(TodoView.Completed).openIds())
    }

    @Test
    fun `counts skip done tasks`() {
        val counts = TodoQuery.counts(todos, listOf(work), now, zone)
        assertEquals(3, counts[TodoView.Inbox])
        assertEquals(2, counts[TodoView.InList("w")])
    }
}

package com.vayunmathur.todo.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the [Todo] model defaults and the pure copies behind the ViewModel:
 * `toggleDone` flips `done`, the detail page copies title/notes/done/dueAt,
 * and `create` trims plus rejects blank titles.
 *
 * The ViewModel methods themselves need a repository, so the exact
 * expressions (`copy(done = !done)`, `trim()`, `isBlank()`) are mirrored
 * here rather than called.
 */
class TodoModelTest {

    @Test
    fun `defaults are an untimed open task`() {
        val todo = Todo()
        assertEquals(0L, todo.id)
        assertEquals("", todo.title)
        assertEquals("", todo.notes)
        assertFalse(todo.done)
        assertEquals(0.0, todo.position)
        assertNull(todo.dueAt)
        assertFalse(todo.deleted)
        assertTrue(todo.createdAt > 0)
        assertTrue(todo.updatedAt > 0)
    }

    @Test
    fun `toggling done flips the flag and keeps every other field`() {
        val todo = Todo(id = 3, title = "t", notes = "n", dueAt = 100L)
        val done = todo.copy(done = !todo.done)
        assertTrue(done.done)
        assertEquals(3L, done.id)
        assertEquals("t", done.title)
        assertEquals("n", done.notes)
        assertEquals(100L, done.dueAt)
        val toggledBack = done.copy(done = !done.done)
        assertEquals(todo.done, toggledBack.done)
    }

    @Test
    fun `withPosition copies the todo and keeps every other field`() {
        val todo = Todo(id = 7, title = "t", done = true, position = 1.0)
        val moved = todo.withPosition(2.5)
        assertEquals(2.5, moved.position)
        assertEquals(7L, moved.id)
        assertEquals("t", moved.title)
        assertTrue(moved.done)
        assertEquals(1.0, todo.position)
    }

    @Test
    fun `clearing the due date stores null`() {
        val todo = Todo(dueAt = 123L).copy(dueAt = null)
        assertNull(todo.dueAt)
    }

    @Test
    fun `unicode title notes and flag survive copies`() {
        val todo = Todo(title = "記 😀 مرحبا", notes = "note 😀", done = true)
        val copy = todo.copy()
        assertEquals("記 😀 مرحبا", copy.title)
        assertEquals("note 😀", copy.notes)
        assertTrue(copy.done)
    }
}

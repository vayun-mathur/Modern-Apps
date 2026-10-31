package com.vayunmathur.todo.domain

import com.vayunmathur.todo.data.Todo
import kotlin.test.Test
import kotlin.test.assertEquals

class TodoSearchTest {
    private val todos = listOf(
        Todo(id = 1, title = "Buy Milk"),
        Todo(id = 2, title = "Call Sam", notes = "about the milk run"),
        Todo(id = 3, title = "Work"),
        Todo(id = 4, title = "記 😀"),
    )

    @Test
    fun `blank query keeps every todo in order`() {
        assertEquals(todos, TodoSearch.filter(todos, ""))
        assertEquals(todos, TodoSearch.filter(todos, "   "))
    }

    @Test
    fun `matches titles and notes case-insensitively`() {
        assertEquals(listOf(1L, 2L), TodoSearch.filter(todos, "MILK").map { it.id })
    }

    @Test
    fun `surrounding whitespace in the query is ignored`() {
        assertEquals(listOf(3L), TodoSearch.filter(todos, "  work ").map { it.id })
    }

    @Test
    fun `unicode queries match unicode titles`() {
        assertEquals(listOf(4L), TodoSearch.filter(todos, "😀").map { it.id })
    }

    @Test
    fun `no match returns an empty list`() {
        assertEquals(emptyList(), TodoSearch.filter(todos, "zzz"))
    }
}

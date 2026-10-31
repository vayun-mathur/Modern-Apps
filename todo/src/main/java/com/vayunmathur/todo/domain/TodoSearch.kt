package com.vayunmathur.todo.domain

import com.vayunmathur.todo.data.Todo

object TodoSearch {
    fun matches(todo: Todo, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return todo.title.contains(q, ignoreCase = true) || todo.notes.contains(q, ignoreCase = true)
    }

    fun filter(todos: List<Todo>, query: String): List<Todo> =
        if (query.isBlank()) todos else todos.filter { matches(it, query) }
}

package com.vayunmathur.todo.domain

import com.vayunmathur.todo.data.Todo

object TodoSearch {
    fun matches(todo: Todo, query: String): Boolean {
        val q = query.trim().removePrefix("#")
        if (q.isEmpty()) return true
        return todo.title.contains(q, ignoreCase = true) ||
            todo.notes.contains(q, ignoreCase = true) ||
            todo.tags.any { it.contains(q, ignoreCase = true) }
    }

    fun filter(todos: List<Todo>, query: String): List<Todo> =
        if (query.isBlank()) todos else todos.filter { matches(it, query) }

    /** Character ranges in [text] matching [query], for highlighting. */
    fun matchRanges(text: String, query: String): List<IntRange> {
        val q = query.trim().removePrefix("#")
        if (q.isEmpty()) return emptyList()
        val out = mutableListOf<IntRange>()
        var from = 0
        while (true) {
            val i = text.indexOf(q, from, ignoreCase = true)
            if (i < 0) return out
            out += i until i + q.length
            from = i + q.length
        }
    }
}

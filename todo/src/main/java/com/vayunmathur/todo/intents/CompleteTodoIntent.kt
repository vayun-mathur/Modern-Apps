package com.vayunmathur.todo.intents

import com.vayunmathur.library.intents.todo.CompleteTodoData
import com.vayunmathur.library.util.AssistantIntent
import com.vayunmathur.todo.data.TodoRepository
import kotlinx.serialization.builtins.serializer

/** Assistant tool `complete_todo`: by sync id, or by exact (case-insensitive) title. */
class CompleteTodoIntent : AssistantIntent<CompleteTodoData, Boolean>(CompleteTodoData.serializer(), Boolean.serializer()) {
    override suspend fun performCalculation(input: CompleteTodoData): Boolean {
        val repo = TodoRepository.get(this)
        val todo = input.id.takeIf { it.isNotBlank() }?.let { repo.getBySyncId(it) }?.takeIf { !it.deleted }
            ?: repo.getAll().firstOrNull { !it.done && it.title.equals(input.title.trim(), ignoreCase = true) }
            ?: return false
        if (!todo.done) repo.setDone(todo, true)
        return true
    }
}

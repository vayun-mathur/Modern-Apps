package com.vayunmathur.todo.intents

import com.vayunmathur.library.intents.todo.InsertTodoData
import com.vayunmathur.library.util.AssistantIntent
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.TodoRepository
import com.vayunmathur.todo.domain.QuickAddParser
import kotlinx.datetime.TimeZone
import kotlinx.serialization.builtins.serializer

/** Assistant tool `create_todo`. Quick-add syntax in the title is honoured when no due time is given. */
class InsertTodoIntent : AssistantIntent<InsertTodoData, String>(InsertTodoData.serializer(), String.serializer()) {
    override suspend fun performCalculation(input: InsertTodoData): String {
        val repo = TodoRepository.get(this)
        val lists = repo.getLists().map { QuickAddParser.ListName(it.syncId, it.name) }
        val now = System.currentTimeMillis()
        val parsed = QuickAddParser.parse(input.title, now, TimeZone.currentSystemDefault(), lists)
        val useParsed = input.dueAt == null
        val todo = Todo(
            title = (if (useParsed) parsed.title else input.title).trim().ifEmpty { input.title.trim() },
            notes = input.notes,
            dueAt = input.dueAt ?: parsed.dueAt,
            dueAllDay = if (useParsed) parsed.dueAllDay else false,
            recurrence = parsed.recurrence?.serialize()?.takeIf { useParsed },
            tags = if (useParsed) parsed.tags else emptyList(),
            priority = input.priority.takeIf { it in 1..Todo.PRIORITY_MAX } ?: if (useParsed) parsed.priority else 0,
            listSyncId = parsed.listSyncId.takeIf { useParsed },
            position = repo.nextPosition(),
            createdAt = now,
        )
        repo.upsert(todo)
        return todo.syncId
    }
}

package com.vayunmathur.todo.intents

import com.vayunmathur.library.intents.todo.GetTodosQuery
import com.vayunmathur.library.intents.todo.TodoData
import com.vayunmathur.library.util.AssistantIntent
import com.vayunmathur.todo.data.TodoRepository
import com.vayunmathur.todo.domain.TodoQuery
import com.vayunmathur.todo.domain.TodoView
import kotlinx.datetime.TimeZone
import kotlinx.serialization.builtins.ListSerializer

/** Assistant tool `get_todos`: open tasks for today, upcoming, or all. */
class GetTodosIntent : AssistantIntent<GetTodosQuery, List<TodoData>>(
    GetTodosQuery.serializer(),
    ListSerializer(TodoData.serializer()),
) {
    override suspend fun performCalculation(input: GetTodosQuery): List<TodoData> {
        val repo = TodoRepository.get(this)
        val lists = repo.getLists()
        val view = when (input.scope.lowercase()) {
            "upcoming" -> TodoView.Upcoming
            "all" -> TodoView.All
            else -> TodoView.Today
        }
        val model = TodoQuery.build(
            TodoQuery.Input(repo.getAll(), lists, view, now = System.currentTimeMillis(), zone = TimeZone.currentSystemDefault()),
        )
        return model.groups.flatMap { it.rows }.take(MAX_RESULTS).map { row ->
            val t = row.todo
            TodoData(t.syncId, t.title, t.notes, t.dueAt, t.done, t.priority, row.list?.name)
        }
    }

    private companion object {
        /** Keeps the reply well under the binder transaction limit. */
        const val MAX_RESULTS = 200
    }
}

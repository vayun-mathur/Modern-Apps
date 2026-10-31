package com.vayunmathur.todo.domain

import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.TodoList
import kotlinx.datetime.TimeZone

/** One task as the list draws it, with everything derived from its neighbours. */
data class TodoRow(
    val todo: Todo,
    val subtasksDone: Int = 0,
    val subtasksTotal: Int = 0,
    val list: TodoList? = null,
    val blocked: Boolean = false,
    val overdue: Boolean = false,
)

sealed interface GroupKey {
    data object None : GroupKey
    data class Bucket(val bucket: DueBucket) : GroupKey
    /** [name] null is "no section". */
    data class Section(val name: String?) : GroupKey
}

data class TodoGroup(val key: GroupKey, val rows: List<TodoRow>)

data class TodoListModel(
    val groups: List<TodoGroup> = emptyList(),
    val completed: List<TodoRow> = emptyList(),
    val canReorder: Boolean = false,
) {
    val openCount: Int get() = groups.sumOf { it.rows.size }
    val isEmpty: Boolean get() = openCount == 0 && completed.isEmpty()
}

/** Builds what the list shows for a view, search, and sort. */
object TodoQuery {

    data class Input(
        val todos: List<Todo>,
        val lists: List<TodoList>,
        val view: TodoView,
        val query: String = "",
        val sort: SortMode = SortMode.MANUAL,
        val now: Long,
        val zone: TimeZone,
    )

    fun build(input: Input): TodoListModel {
        val listsById = input.lists.associateBy { it.syncId }
        val rows = toRows(input.todos, listsById, input.now, input.zone)
        val searching = input.query.isNotBlank()
        val visible = if (searching) {
            rows.filter { TodoSearch.matches(it.todo, input.query) }
        } else {
            rows.filter { inView(it, input) }
        }
        if (input.view == TodoView.Completed && !searching) {
            val done = visible.filter { it.todo.done }.sortedByDescending { it.todo.completedAt ?: it.todo.updatedAt }
            return TodoListModel(groups = listOf(TodoGroup(GroupKey.None, done)))
        }
        val (open, done) = visible.partition { !it.todo.done }
        val sorted = open.sortedWith(comparator(input.sort))
        val groups = group(sorted, input, listsById, searching)
        return TodoListModel(
            groups = groups,
            completed = done.sortedByDescending { it.todo.completedAt ?: it.todo.updatedAt },
            canReorder = input.sort == SortMode.MANUAL && !searching && groups.size <= 1,
        )
    }

    fun toRows(todos: List<Todo>, listsById: Map<String, TodoList>, now: Long, zone: TimeZone): List<TodoRow> {
        val bySync = todos.associateBy { it.syncId }
        val children = todos.filter { it.parentSyncId != null }.groupBy { it.parentSyncId }
        return todos.filter { it.parentSyncId == null }.map { todo ->
            val subs = children[todo.syncId].orEmpty()
            TodoRow(
                todo = todo,
                subtasksDone = subs.count { it.done },
                subtasksTotal = subs.size,
                list = todo.listSyncId?.let { listsById[it] },
                blocked = isBlocked(todo, bySync),
                overdue = DueBuckets.isOverdue(todo, now, zone),
            )
        }
    }

    fun isBlocked(todo: Todo, bySync: Map<String, Todo>): Boolean =
        todo.blockedBy.any { id -> bySync[id]?.let { !it.done && !it.deleted } == true }

    private fun inView(row: TodoRow, input: Input): Boolean {
        val todo = row.todo
        val started = todo.startAt == null || todo.startAt <= input.now
        return when (val view = input.view) {
            TodoView.Inbox -> todo.listSyncId == null && started
            TodoView.Today -> started && !row.blocked && (todo.done || DueBuckets.isDueByToday(todo, input.now, input.zone)) &&
                (!todo.done || completedToday(todo, input))
            TodoView.Upcoming -> started && todo.dueAt != null && !row.overdue
            TodoView.Priority -> started && todo.priority != Todo.PRIORITY_NONE
            TodoView.All -> true
            TodoView.Completed -> todo.done
            is TodoView.InList -> todo.listSyncId == view.listSyncId
            is TodoView.Tagged -> started && todo.tags.any { it.equals(view.tag, ignoreCase = true) }
        }
    }

    private fun completedToday(todo: Todo, input: Input): Boolean {
        val at = todo.completedAt ?: return false
        return DueBuckets.dayDelta(at, input.now, input.zone) == 0
    }

    private fun group(rows: List<TodoRow>, input: Input, listsById: Map<String, TodoList>, searching: Boolean): List<TodoGroup> {
        if (searching) return listOf(TodoGroup(GroupKey.None, rows))
        val byBucket = input.sort == SortMode.DUE || input.view == TodoView.Today || input.view == TodoView.Upcoming
        val view = input.view
        return when {
            byBucket -> rows.groupBy { DueBuckets.bucket(it.todo, input.now, input.zone) }
                .toSortedMap()
                .map { (bucket, items) -> TodoGroup(GroupKey.Bucket(bucket), items) }
            view is TodoView.InList && listsById[view.listSyncId]?.sections.orEmpty().isNotEmpty() ->
                sectionGroups(rows, listsById.getValue(view.listSyncId).sections)
            else -> listOf(TodoGroup(GroupKey.None, rows))
        }.ifEmpty { listOf(TodoGroup(GroupKey.None, emptyList())) }
    }

    private fun sectionGroups(rows: List<TodoRow>, sections: List<String>): List<TodoGroup> {
        val bySection = rows.groupBy { row -> row.todo.section?.takeIf { it in sections } }
        val unsectioned = bySection[null].orEmpty()
        val named = sections.map { TodoGroup(GroupKey.Section(it), bySection[it].orEmpty()) }
        return (if (unsectioned.isEmpty()) emptyList() else listOf(TodoGroup(GroupKey.Section(null), unsectioned))) + named
    }

    fun comparator(sort: SortMode): Comparator<TodoRow> {
        val byPosition = compareBy<TodoRow> { it.todo.position }
        return when (sort) {
            SortMode.MANUAL -> byPosition
            SortMode.DUE -> compareBy<TodoRow, Long?>(nullsLast()) { it.todo.dueAt }
                .then(compareBy { priorityRank(it.todo) }).then(byPosition)
            SortMode.PRIORITY -> compareBy<TodoRow> { priorityRank(it.todo) }
                .then(compareBy(nullsLast()) { it.todo.dueAt }).then(byPosition)
            SortMode.CREATED -> compareByDescending<TodoRow> { it.todo.createdAt }
            SortMode.TITLE -> compareBy<TodoRow, String>(String.CASE_INSENSITIVE_ORDER) { it.todo.title }.then(byPosition)
        }
    }

    private fun priorityRank(todo: Todo): Int =
        if (todo.priority == Todo.PRIORITY_NONE) Todo.PRIORITY_MAX + 1 else todo.priority

    /** Open top-level tasks per view, for drawer badges. */
    fun counts(todos: List<Todo>, lists: List<TodoList>, now: Long, zone: TimeZone): Map<TodoView, Int> {
        val listsById = lists.associateBy { it.syncId }
        val rows = toRows(todos, listsById, now, zone).filter { !it.todo.done }
        val views = TodoView.smartViews.filter { it != TodoView.Completed && it != TodoView.All } +
            lists.map { TodoView.InList(it.syncId) }
        return views.associateWith { view ->
            val input = Input(todos, lists, view, now = now, zone = zone)
            rows.count { inView(it, input) }
        }
    }

    fun allTags(todos: List<Todo>): List<String> =
        todos.flatMap { it.tags }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
}

package com.vayunmathur.todo.platform

import com.vayunmathur.todo.data.getLists
import com.vayunmathur.todo.data.nextListPosition
import com.vayunmathur.todo.data.upsertList
import android.content.Context
import android.net.Uri
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.TodoList
import com.vayunmathur.todo.data.TodoRepository
import com.vayunmathur.todo.data.newSyncId
import com.vayunmathur.todo.domain.Positions
import com.vayunmathur.todo.domain.format.ExportFormat
import com.vayunmathur.todo.domain.format.ExportSource
import com.vayunmathur.todo.domain.format.ImportedTask
import com.vayunmathur.todo.domain.format.TodoCsv
import com.vayunmathur.todo.domain.format.TodoTxt
import com.vayunmathur.todo.domain.format.VTodo
import kotlinx.datetime.TimeZone

/** Streams todo.txt / CSV / iCalendar files through the Storage Access Framework. */
class TodoDataTransfer(private val context: Context, private val repo: TodoRepository) {

    suspend fun export(uri: Uri, format: ExportFormat) {
        val source = ExportSource(repo.getAll(), repo.getLists())
        val zone = TimeZone.currentSystemDefault()
        val text = when (format) {
            ExportFormat.TODO_TXT -> TodoTxt.export(source, zone)
            ExportFormat.CSV -> TodoCsv.export(source, zone)
            ExportFormat.ICALENDAR -> VTodo.export(source)
        }
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    /** Reads a file and picks the format from its content. */
    fun read(uri: Uri): List<ImportedTask> {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        val zone = TimeZone.currentSystemDefault()
        return when {
            text.contains("BEGIN:VCALENDAR", ignoreCase = true) -> VTodo.parse(text, zone)
            text.lineSequence().firstOrNull().orEmpty().startsWith("Title,", true) -> TodoCsv.parse(text, zone)
            else -> TodoTxt.parse(text, zone)
        }.filter { it.title.isNotBlank() }
    }

    /** Inserts [tasks] with fresh identities, creating missing lists by name. Returns the count. */
    suspend fun import(tasks: List<ImportedTask>): Int = repo.inTransaction {
        val lists = repo.getLists().associateBy { it.name.lowercase() }.toMutableMap()
        val idsByRef = tasks.mapNotNull { it.ref }.associateWith { newSyncId() }
        var position = repo.nextPosition()
        val now = System.currentTimeMillis()
        for (task in tasks) {
            val listSyncId = task.listName?.let { name ->
                lists.getOrPut(name.lowercase()) {
                    TodoList(name = name, position = repo.nextListPosition()).also { repo.upsertList(it) }
                }.syncId
            }
            repo.upsert(
                Todo(
                    syncId = task.ref?.let { idsByRef[it] } ?: newSyncId(),
                    title = task.title,
                    notes = task.notes,
                    done = task.done,
                    dueAt = task.dueAt,
                    dueAllDay = task.dueAllDay,
                    priority = task.priority,
                    listSyncId = listSyncId,
                    section = task.section,
                    tags = task.tags,
                    recurrence = task.recurrence,
                    startAt = task.startAt,
                    createdAt = task.createdAt ?: now,
                    completedAt = task.completedAt ?: if (task.done) now else null,
                    parentSyncId = task.parentRef?.let { idsByRef[it] },
                    position = position,
                ),
            )
            position = Positions.after(position)
        }
        tasks.size
    }
}

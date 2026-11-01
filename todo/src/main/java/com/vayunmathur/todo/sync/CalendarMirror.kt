package com.vayunmathur.todo.sync

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.core.content.ContextCompat
import com.vayunmathur.library.log.Log
import com.vayunmathur.todo.R
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.domain.DueDates
import com.vayunmathur.todo.domain.Recurrence
import kotlinx.datetime.TimeZone

/**
 * Mirrors open tasks with a due date into a read-only local "Tasks" calendar, so any calendar
 * app shows them. One event per task, keyed by `_SYNC_ID` = the task's sync id; `SYNC_DATA1`
 * holds a fingerprint so unchanged tasks are not rewritten. Turning the mirror off deletes
 * the calendar.
 */
object CalendarMirror {
    private const val TAG = "CalendarMirror"
    private const val ACCOUNT = "Todo"
    private const val CALENDAR_NAME = "todo_tasks"
    private const val COLOR = 0xFF3F51B5.toInt()
    private const val TIMED_DURATION = "PT30M"
    private const val ALL_DAY_DURATION = "P1D"
    private const val TIMED_LENGTH_MS = 30 * 60_000L
    private const val DAY_MS = 86_400_000L

    fun hasPermission(context: Context): Boolean = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    // Broad catch: the calendar provider throws undocumented runtime exceptions, and a mirror
    // failure must never break the app.
    @Suppress("TooGenericExceptionCaught")
    fun reconcile(context: Context, enabled: Boolean, todos: List<Todo>) {
        if (!hasPermission(context)) return
        try {
            if (!enabled) {
                findCalendar(context)?.let { context.contentResolver.delete(asSync(ContentUris.withAppendedId(Calendars.CONTENT_URI, it)), null, null) }
                return
            }
            val calendarId = findCalendar(context) ?: createCalendar(context) ?: return
            write(context, calendarId, todos.filter { !it.done && !it.deleted && it.dueAt != null && it.parentSyncId == null })
        } catch (e: Exception) {
            Log.error(TAG, "reconcile failed", e)
        }
    }

    private fun write(context: Context, calendarId: Long, wanted: List<Todo>) {
        val existing = mutableMapOf<String, Pair<Long, String?>>()
        context.contentResolver.query(
            asSync(Events.CONTENT_URI),
            arrayOf(Events._ID, Events._SYNC_ID, Events.SYNC_DATA1),
            "${Events.CALENDAR_ID} = ?",
            arrayOf(calendarId.toString()),
            null,
        )?.use { c -> while (c.moveToNext()) c.getString(1)?.let { existing[it] = c.getLong(0) to c.getString(2) } }

        val ops = ArrayList<ContentProviderOperation>()
        val zone = TimeZone.currentSystemDefault()
        for (todo in wanted) {
            val values = eventValues(todo, calendarId, zone)
            val fingerprint = values.getAsString(Events.SYNC_DATA1)
            val current = existing.remove(todo.syncId)
            when {
                current == null -> ops += ContentProviderOperation.newInsert(asSync(Events.CONTENT_URI)).withValues(values).build()
                current.second != fingerprint -> ops += ContentProviderOperation
                    .newUpdate(asSync(ContentUris.withAppendedId(Events.CONTENT_URI, current.first))).withValues(values).build()
            }
        }
        existing.values.forEach { (id, _) ->
            ops += ContentProviderOperation.newDelete(asSync(ContentUris.withAppendedId(Events.CONTENT_URI, id))).build()
        }
        if (ops.isNotEmpty()) context.contentResolver.applyBatch(android.provider.CalendarContract.AUTHORITY, ops)
    }

    /** Calendar rules: all-day events are UTC midnights; recurring events use DURATION, not DTEND. */
    private fun eventValues(todo: Todo, calendarId: Long, zone: TimeZone): ContentValues {
        val due = todo.dueAt!!
        val start = if (todo.dueAllDay) DueDates.startOfDay(DueDates.localDate(due, zone), TimeZone.UTC) else due
        val rrule = Recurrence.parse(todo.recurrence)?.toRRule()
        return ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId)
            put(Events._SYNC_ID, todo.syncId)
            put(Events.TITLE, todo.title)
            put(Events.DESCRIPTION, todo.notes)
            put(Events.DTSTART, start)
            put(Events.ALL_DAY, if (todo.dueAllDay) 1 else 0)
            put(Events.EVENT_TIMEZONE, if (todo.dueAllDay) "UTC" else zone.id)
            if (rrule != null) {
                put(Events.RRULE, rrule)
                put(Events.DURATION, if (todo.dueAllDay) ALL_DAY_DURATION else TIMED_DURATION)
                putNull(Events.DTEND)
            } else {
                put(Events.DTEND, start + if (todo.dueAllDay) DAY_MS else TIMED_LENGTH_MS)
                putNull(Events.RRULE)
                putNull(Events.DURATION)
            }
            put(Events.SYNC_DATA1, listOf(todo.title, todo.notes, start, todo.dueAllDay, rrule, zone.id).joinToString("\u0000").hashCode().toString())
        }
    }

    private fun findCalendar(context: Context): Long? = context.contentResolver.query(
        Calendars.CONTENT_URI,
        arrayOf(Calendars._ID),
        "${Calendars.ACCOUNT_NAME} = ? AND ${Calendars.ACCOUNT_TYPE} = ? AND ${Calendars.NAME} = ?",
        arrayOf(ACCOUNT, android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL, CALENDAR_NAME),
        null,
    )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    private fun createCalendar(context: Context): Long? {
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, ACCOUNT)
            put(Calendars.ACCOUNT_TYPE, android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, CALENDAR_NAME)
            put(Calendars.CALENDAR_DISPLAY_NAME, context.getString(R.string.calendar_name))
            put(Calendars.CALENDAR_COLOR, COLOR)
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_READ)
            put(Calendars.OWNER_ACCOUNT, ACCOUNT)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
        }
        return context.contentResolver.insert(asSync(Calendars.CONTENT_URI), values)?.let { ContentUris.parseId(it) }
    }

    private fun asSync(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(android.provider.CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, ACCOUNT)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()
}

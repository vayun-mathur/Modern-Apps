package com.vayunmathur.todo.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.core.net.toUri
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.domain.Reminders
import kotlinx.datetime.TimeZone

/**
 * Keeps one exact alarm per pending task reminder.
 *
 * [reconcile] is a full pass — schedule everything wanted, cancel everything tracked that no
 * longer is — so it stays correct across edits, completions, deletes and sync pulls without
 * per-change bookkeeping. Falls back to inexact alarms when exact ones are not allowed.
 */
object ReminderScheduler {
    const val ACTION_REMINDER = "com.vayunmathur.todo.action.REMINDER"
    const val EXTRA_SYNC_ID = "sync_id"

    private const val PREFS = "todo_reminders"
    private const val KEY_SCHEDULED = "scheduled"

    fun reconcile(context: Context, todos: List<Todo>, now: Long = System.currentTimeMillis()) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty()
        val zone = TimeZone.currentSystemDefault()
        val pending = todos.mapNotNull { todo -> Reminders.nextFire(todo, now, zone)?.let { todo.syncId to it } }
        val wanted = pending.map { it.first }.toSet()
        (previous - wanted).forEach { alarms.cancel(intent(context, it)) }
        pending.forEach { (syncId, at) -> schedule(context, alarms, syncId, at) }
        prefs.edit { putStringSet(KEY_SCHEDULED, wanted) }
    }

    private fun schedule(context: Context, alarms: AlarmManager, syncId: String, at: Long) =
        alarms.arm(at, intent(context, syncId))

    /** The data URI keeps each task's PendingIntent distinct. */
    private fun intent(context: Context, syncId: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        syncId.hashCode(),
        Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_REMINDER)
            .setData("todo://reminder/$syncId".toUri())
            .putExtra(EXTRA_SYNC_ID, syncId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

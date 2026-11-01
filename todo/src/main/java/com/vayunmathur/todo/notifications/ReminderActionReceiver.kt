package com.vayunmathur.todo.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vayunmathur.todo.data.TodoRepository
import com.vayunmathur.todo.domain.DueDates
import com.vayunmathur.todo.domain.Reminders
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus

/** Complete / snooze from a reminder notification. Rescheduling follows from the data change. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val syncId = intent.getStringExtra(ReminderScheduler.EXTRA_SYNC_ID) ?: return
        val app = context.applicationContext
        val repo = TodoRepository.get(app)
        val pending = goAsync()
        repo.appScope.launch {
            try {
                ReminderNotifications.cancel(app, syncId)
                val todo = repo.getBySyncId(syncId) ?: return@launch
                when (intent.action) {
                    ACTION_COMPLETE -> repo.setDone(todo, true)
                    ACTION_SNOOZE -> {
                        val until = snoozeUntil(intent.getIntExtra(EXTRA_SNOOZE, SNOOZE_HOUR))
                        repo.upsert(todo.copy(reminderAt = until))
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun snoozeUntil(kind: Int, now: Long = System.currentTimeMillis()): Long = when (kind) {
        SNOOZE_TOMORROW -> {
            val zone = TimeZone.currentSystemDefault()
            DueDates.at(DueDates.localDate(now, zone).plus(1, DateTimeUnit.DAY), Reminders.ALL_DAY_TIME, zone)
        }
        else -> now + HOUR_MS
    }

    companion object {
        const val ACTION_COMPLETE = "com.vayunmathur.todo.action.COMPLETE"
        const val ACTION_SNOOZE = "com.vayunmathur.todo.action.SNOOZE"
        const val EXTRA_SNOOZE = "snooze"
        const val SNOOZE_HOUR = 1
        const val SNOOZE_TOMORROW = 2
        private const val HOUR_MS = 3_600_000L
    }
}

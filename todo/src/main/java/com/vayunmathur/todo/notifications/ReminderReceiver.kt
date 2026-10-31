package com.vayunmathur.todo.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vayunmathur.todo.data.TodoRepository
import com.vayunmathur.todo.data.TodoSettings
import com.vayunmathur.todo.domain.DueBuckets
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

/** Fires a task's reminder, or the daily digest. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val repo = TodoRepository.get(app)
        val pending = goAsync()
        repo.appScope.launch {
            try {
                when (intent.action) {
                    ReminderScheduler.ACTION_REMINDER -> remind(app, repo, intent.getStringExtra(ReminderScheduler.EXTRA_SYNC_ID))
                    DigestScheduler.ACTION_DIGEST -> digest(app, repo)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun remind(context: Context, repo: TodoRepository, syncId: String?) {
        val todo = syncId?.let { repo.getBySyncId(it) } ?: return
        val at = todo.reminderAt ?: return
        // A reminder that moved later since this alarm was set is re-armed by reconcile.
        if (todo.done || todo.deleted || at > System.currentTimeMillis() + EARLY_SLACK_MS) return
        val channelName = todo.listSyncId?.let { repo.getListBySyncId(it)?.name } ?: context.getString(com.vayunmathur.todo.R.string.view_inbox)
        ReminderNotifications.postReminder(context, todo, channelName)
    }

    private suspend fun digest(context: Context, repo: TodoRepository) {
        val settings = TodoSettings(context)
        val now = System.currentTimeMillis()
        val zone = TimeZone.currentSystemDefault()
        val open = repo.getAll().filter { !it.done && it.parentSyncId == null }
        val overdue = open.count { DueBuckets.isOverdue(it, now, zone) }
        val today = open.count { it.dueAt != null && !DueBuckets.isOverdue(it, now, zone) && DueBuckets.isDueByToday(it, now, zone) }
        if (today + overdue > 0) ReminderNotifications.postDigest(context, today, overdue)
        DigestScheduler.schedule(context, settings.digestEnabledNow(), settings.digestMinutesNow(), now + 1)
    }

    private companion object {
        const val EARLY_SLACK_MS = 60_000L
    }
}

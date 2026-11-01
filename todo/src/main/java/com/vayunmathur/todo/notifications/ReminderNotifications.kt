package com.vayunmathur.todo.notifications

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.is24Hour
import com.vayunmathur.library.util.MaNotifications
import com.vayunmathur.todo.MainActivity
import com.vayunmathur.todo.R
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.domain.DueDates
import com.vayunmathur.todo.platform.IntentRouter
import kotlinx.datetime.TimeZone

/** Builds and posts the reminder and digest notifications. */
object ReminderNotifications {
    private const val DIGEST_ID = 0x7D16E5
    private const val COMPLETE = ReminderActionReceiver.ACTION_COMPLETE
    private const val SNOOZE = ReminderActionReceiver.ACTION_SNOOZE
    private const val IN_HOUR = ReminderActionReceiver.SNOOZE_HOUR
    private const val TOMORROW = ReminderActionReceiver.SNOOZE_TOMORROW

    fun notificationId(syncId: String): Int = syncId.hashCode()

    fun postReminder(context: Context, todo: Todo, channelName: String) {
        val channel = NotificationChannels.forList(todo.listSyncId)
        val id = notificationId(todo.syncId)
        MaNotifications.post(
            context = context,
            channelId = channel,
            channelName = channelName,
            importance = android.app.NotificationManager.IMPORTANCE_HIGH,
            notificationId = id,
            launchIntent = openTaskIntent(context, todo.syncId),
        ) { builder ->
            builder
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(todo.title.ifBlank { context.getString(R.string.untitled) })
                .setContentText(dueText(context, todo))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .addAction(0, context.getString(R.string.complete_action), action(context, todo.syncId, COMPLETE, 0))
                .addAction(0, context.getString(R.string.snooze_hour), action(context, todo.syncId, SNOOZE, IN_HOUR))
                .addAction(
                    0,
                    context.getString(R.string.snooze_tomorrow),
                    action(context, todo.syncId, SNOOZE, TOMORROW),
                )
        }
    }

    fun postDigest(context: Context, today: Int, overdue: Int) {
        NotificationChannels.ensureDigest(context)
        val text = listOfNotNull(
            today.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.digest_today, it, it) },
            overdue.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.digest_overdue, it, it) },
        ).joinToString(", ")
        MaNotifications.post(
            context = context,
            channelId = NotificationChannels.DIGEST,
            channelName = context.getString(R.string.digest_channel),
            notificationId = DIGEST_ID,
            launchIntent = Intent(context, MainActivity::class.java).putExtra(IntentRouter.EXTRA_VIEW, "today"),
        ) { builder ->
            builder
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.digest_title))
                .setContentText(text)
                .setAutoCancel(true)
        }
    }

    fun cancel(context: Context, syncId: String) =
        NotificationManagerCompat.from(context).cancel(notificationId(syncId))

    fun openTaskIntent(context: Context, syncId: String): Intent =
        Intent(context, MainActivity::class.java)
            .setData("todo://task/$syncId".toUri())
            .putExtra(IntentRouter.EXTRA_OPEN_TASK, syncId)

    private fun action(context: Context, syncId: String, action: String, snooze: Int) = MaNotifications.broadcastIntent(
        context,
        Intent(context, ReminderActionReceiver::class.java)
            .setAction(action)
            .setData("todo://action/$action/$snooze/$syncId".toUri())
            .putExtra(ReminderScheduler.EXTRA_SYNC_ID, syncId)
            .putExtra(ReminderActionReceiver.EXTRA_SNOOZE, snooze),
        requestCode = (syncId + action + snooze).hashCode(),
    )

    private fun dueText(context: Context, todo: Todo): String? {
        val due = todo.dueAt ?: return null
        val zone = TimeZone.currentSystemDefault()
        val date = DateString.dateWeekdayNoYear(DueDates.localDate(due, zone))
        if (todo.dueAllDay) return date
        return date + " " + DateString.time(DueDates.localTime(due, zone), is24Hour(context))
    }
}

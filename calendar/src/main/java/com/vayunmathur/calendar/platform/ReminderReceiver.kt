package com.vayunmathur.calendar.platform

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.text.format.DateFormat
import com.vayunmathur.library.log.Log
import androidx.core.app.NotificationCompat
import com.vayunmathur.calendar.MainActivity
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.library.util.ensureNotificationChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.Date

/**
 * Fires when an event reminder alarm scheduled by [ReminderScheduler] goes off.
 * Posts a notification, then re-reconciles so the next occurrence of a
 * recurring event is scheduled (a no-op for one-off events).
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getLongExtra(ReminderScheduler.EXTRA_EVENT_ID, -1L)
        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE)
            ?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.reminder_notification_default_title)
        val instanceStart = intent.getLongExtra(ReminderScheduler.EXTRA_INSTANCE_START, 0L)
        val instanceEnd = intent.getLongExtra(ReminderScheduler.EXTRA_INSTANCE_END, 0L)

        // The Instance lookup below queries the calendar provider, so the whole
        // post runs off the main thread via goAsync().
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                context.ensureNotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.reminder_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                    context.getString(R.string.reminder_channel_description),
                )

                // Deep-link the tap to the event detail, mirroring the agenda
                // widget's instance extra (see MainActivity.consumeInstanceLink).
                // The alarm only carries ids/times, so resolve the full Instance
                // from the provider here. On failure (locked boot, deleted
                // event) fall back to lightweight extras that MainActivity
                // resolves after unlock, else bare launch (old behavior).
                val instanceJson = lookupInstanceJson(context, eventId, instanceStart, instanceEnd)
                val notificationId = 31 * eventId.hashCode() + instanceStart.hashCode()
                val openIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(ReminderScheduler.EXTRA_EVENT_ID, eventId)
                    putExtra(ReminderScheduler.EXTRA_INSTANCE_START, instanceStart)
                    putExtra(ReminderScheduler.EXTRA_INSTANCE_END, instanceEnd)
                    if (instanceJson != null) putExtra(ReminderScheduler.EXTRA_INSTANCE, instanceJson)
                }
                val contentIntent = PendingIntent.getActivity(
                    context,
                    notificationId,
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

                val timeString = DateFormat.getTimeFormat(context).format(Date(instanceStart))
                // Visibility is left at the platform default (VISIBILITY_PRIVATE) on purpose: that is
                // what lets the system redact this on the lock screen when the user has asked for
                // sensitive content to be hidden. Do not set VISIBILITY_PUBLIC.
                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_reminder_notification)
                    .setContentTitle(title)
                    .setContentText(context.getString(R.string.reminder_notification_text, timeString))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_EVENT)
                    .setContentIntent(contentIntent)
                    .setAutoCancel(true)
                    .build()
                context.getSystemService(NotificationManager::class.java)
                    .notify(notificationId, notification)

                // Reschedule the following occurrence for recurring events.
                // Before the first unlock the provider is unreadable, so there is nothing to
                // reconcile against. The post-unlock BOOT_COMPLETED pass picks this up; the
                // cost is that a recurring reminder firing pre-unlock does not arm its next
                // occurrence until the user unlocks.
                if (context.getSystemService(UserManager::class.java)?.isUserUnlocked != false) {
                    ReminderScheduler.reconcileAll(context)
                }
            } catch (expected: Exception) {
                Log.error(TAG, "could not post reminder notification", expected)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** Notification-channel id and logging tag for the reminder receiver. */
    companion object {
        const val CHANNEL_ID = "calendar_reminders"
        private const val TAG = "CalendarReminderReceiver"
    }

    /**
     * Serializes the reminder's [Instance] for the notification tap deep-link.
     * Null when the provider is unreadable (locked boot) or the event is gone.
     */
    private fun lookupInstanceJson(context: Context, eventId: Long, begin: Long, end: Long): String? {
        if (eventId == -1L || begin == 0L) return null
        return Instance.findForReminder(context, eventId, begin, end)?.let { instance ->
            runCatching { Json.encodeToString(instance) }.getOrNull()
        }
    }
}

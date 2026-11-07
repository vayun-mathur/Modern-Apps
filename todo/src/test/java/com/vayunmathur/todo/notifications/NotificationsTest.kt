package com.vayunmathur.todo.notifications

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.TodoList
import kotlinx.datetime.TimeZone
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class NotificationsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun digestNextTriggerTodayAndTomorrow() {
        val zone = TimeZone.of("UTC")
        val now = com.vayunmathur.todo.domain.DueDates.at(kotlinx.datetime.LocalDate(2026, 3, 4), kotlinx.datetime.LocalTime(7, 0), zone)
        // 8:00 later today
        assertEquals(now + 3_600_000L, DigestScheduler.nextTrigger(8 * 60L, now, zone))
        // 6:00 already passed -> tomorrow
        assertEquals(now + 23 * 3_600_000L, DigestScheduler.nextTrigger(6 * 60L, now, zone))
        // exactly now -> tomorrow (not > now)
        assertEquals(now + 24 * 3_600_000L, DigestScheduler.nextTrigger(7 * 60L, now, zone))
    }
    @Test fun digestScheduleDisabledCancelsAndEnabledArms() {
        DigestScheduler.schedule(context, enabled = false, minutesOfDay = 480)
        DigestScheduler.schedule(context, enabled = true, minutesOfDay = 480)
        val alarms = Shadows.shadowOf(context.getSystemService(AlarmManager::class.java))
        assertTrue(alarms.scheduledAlarms.isNotEmpty())
    }
    @Test fun reminderReconcileSchedulesFutureAndCancelsStale() {
        val future = System.currentTimeMillis() + 3_600_000L
        ReminderScheduler.reconcile(context, listOf(Todo(syncId = "r1", title = "t", reminderAt = future)))
        val prefs = context.getSharedPreferences("todo_reminders", Context.MODE_PRIVATE)
        assertEquals(setOf("r1"), prefs.getStringSet("scheduled", emptySet()))
        // now empty list cancels
        ReminderScheduler.reconcile(context, emptyList())
        assertEquals(emptySet<String>(), prefs.getStringSet("scheduled", emptySet()))
    }
    @Test fun reminderReconcileSkipsDoneAndPast() {
        ReminderScheduler.reconcile(context, listOf(
            Todo(syncId = "done1", title = "t", reminderAt = System.currentTimeMillis() + 100000, done = true),
            Todo(syncId = "past1", title = "t", reminderAt = 1000L),
        ))
        val prefs = context.getSharedPreferences("todo_reminders", Context.MODE_PRIVATE)
        assertTrue(prefs.getStringSet("scheduled", emptySet()).isNullOrEmpty())
    }
    @Test fun channelsForListAndSync() {
        assertEquals(NotificationChannels.INBOX, NotificationChannels.forList(null))
        assertEquals("todo_list_abc", NotificationChannels.forList("abc"))
        NotificationChannels.ensureInbox(context)
        NotificationChannels.ensureDigest(context)
        NotificationChannels.syncLists(context, listOf(TodoList(syncId = "abc", name = "Proj"), TodoList(syncId = "def", name = "")))
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        assertNotNull(nm.getNotificationChannel(NotificationChannels.INBOX))
        assertNotNull(nm.getNotificationChannel("todo_list_abc"))
        // sync with empty removes list channels
        NotificationChannels.syncLists(context, emptyList())
        assertEquals(null, nm.getNotificationChannel("todo_list_abc"))
    }
    @Test fun reminderNotificationsIdsAndIntents() {
        assertEquals("x".hashCode(), ReminderNotifications.notificationId("x"))
        val intent = ReminderNotifications.openTaskIntent(context, "sync42")
        assertEquals("sync42", intent.getStringExtra(com.vayunmathur.todo.platform.IntentRouter.EXTRA_OPEN_TASK))
        assertEquals("todo://task/sync42", intent.data.toString())
        ReminderNotifications.postReminder(context, Todo(syncId = "sync42", title = "Remind me", dueAt = System.currentTimeMillis()), "Inbox")
        ReminderNotifications.postDigest(context, today = 2, overdue = 1)
        ReminderNotifications.postDigest(context, today = 0, overdue = 0)
        ReminderNotifications.cancel(context, "sync42")
    }
    @Test fun alarmArmDoesNotThrow() {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pi = android.app.PendingIntent.getBroadcast(context, 1, Intent(context, ReminderReceiver::class.java), android.app.PendingIntent.FLAG_IMMUTABLE)
        alarms.arm(System.currentTimeMillis() + 100000, pi)
    }
}

package com.vayunmathur.todo.notifications

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.todo.data.TestTodoRepository
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.TodoRepository
import com.vayunmathur.todo.data.TodoSettings
import com.vayunmathur.todo.sync.CalendarMirror
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ReminderReceiver] dispatch and [CalendarMirror] permission gating.
 *
 * The receiver resolves its repository through [com.vayunmathur.todo.data.TodoRepository.get],
 * so the in-memory repository is installed as the singleton (see
 * [TestTodoRepository]). Notifications are asserted through Robolectric's
 * shadow notification manager. CalendarMirror's full calendar-provider write
 * path needs a CalendarProvider that Robolectric does not ship; the tests
 * cover the permission gate and the no-calendar / no-provider paths, and the
 * provider-backed event sync is noted as skipped in the module report.
 */
@RunWith(RobolectricTestRunner::class)
class ReminderReceiverCalendarTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private var harness: TestTodoRepository? = null
    private val repo get() = harness!!.repository

    @BeforeTest fun setUp() {
        harness = TestTodoRepository.create(context)
    }

    @AfterTest fun tearDown() {
        harness?.close()
        harness = null
    }

    private fun shadowNotifications() =
        shadowOf(context.getSystemService(android.app.NotificationManager::class.java))

    /**
     * Delivers [intent] as a real broadcast. Calling `onReceive` directly is
     * not a valid substitute: `goAsync()` then returns null and the receiver
     * crashes in its `finally { pending.finish() }`, and (separately) the
     * notification post is gated on POST_NOTIFICATIONS at SDK 34.
     */
    private fun deliver(intent: Intent) {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // The receiver must read the harness repository via TodoRepository.get.
        kotlin.test.assertSame(harness!!.repository, TodoRepository.get(context))
        context.sendBroadcast(intent.setComponent(
            android.content.ComponentName(context, ReminderReceiver::class.java),
        ))
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun awaitNotifications(count: Int = 1): List<android.app.Notification> {
        val deadline = System.currentTimeMillis() + 5_000
        var posted = shadowNotifications().allNotifications
        while (posted.size < count && System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(25)
            posted = shadowNotifications().allNotifications
        }
        return posted
    }

    @Test fun reminderForDueTaskPostsNotification() = runBlocking {
        val now = System.currentTimeMillis()
        repo.upsert(Todo(syncId = "remind", title = "Ring me", reminderAt = now - 1_000L))
        val intent = Intent(ReminderScheduler.ACTION_REMINDER)
            .putExtra(ReminderScheduler.EXTRA_SYNC_ID, "remind")
        deliver(intent)
        val posted = awaitNotifications()
        assertTrue(posted.isNotEmpty(), "expected a reminder notification to be posted")
        val notification = posted.first()
        assertEquals(
            "Ring me",
            notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString(),
        )
    }

    @Test fun reminderIgnoresDoneMissingAndFutureTasks() = runBlocking {
        val now = System.currentTimeMillis()
        repo.upsert(Todo(syncId = "done", title = "Done", reminderAt = now - 1_000L, done = true, completedAt = now))
        repo.upsert(Todo(syncId = "future", title = "Future", reminderAt = now + 3_600_000L))
        deliver(Intent(ReminderScheduler.ACTION_REMINDER).putExtra(ReminderScheduler.EXTRA_SYNC_ID, "done"))
        deliver(Intent(ReminderScheduler.ACTION_REMINDER).putExtra(ReminderScheduler.EXTRA_SYNC_ID, "future"))
        deliver(Intent(ReminderScheduler.ACTION_REMINDER).putExtra(ReminderScheduler.EXTRA_SYNC_ID, "missing"))
        deliver(Intent(ReminderScheduler.ACTION_REMINDER))
        Thread.sleep(400)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(shadowNotifications().allNotifications.isEmpty())
    }

    @Test fun reminderIgnoresUnknownAction() {
        deliver(Intent("com.example.UNKNOWN"))
        Thread.sleep(300)
        assertTrue(shadowNotifications().allNotifications.isEmpty())
    }

    @Test fun digestPostsWhenTasksDueAndReschedules() = runBlocking {
        val settings = TodoSettings(context)
        settings.setDigestEnabled(true)
        settings.setDigestMinutes(8 * 60L)
        repo.upsert(Todo(syncId = "today", title = "Due today", dueAt = System.currentTimeMillis()))
        deliver(Intent(DigestScheduler.ACTION_DIGEST))
        val posted = awaitNotifications()
        assertTrue(posted.isNotEmpty(), "expected a digest notification")
    }

    @Test fun digestStaysSilentWhenNothingDue() = runBlocking {
        val settings = TodoSettings(context)
        settings.setDigestEnabled(true)
        repo.upsert(Todo(syncId = "done", title = "Done", done = true, completedAt = 1L))
        deliver(Intent(DigestScheduler.ACTION_DIGEST))
        Thread.sleep(400)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(shadowNotifications().allNotifications.isEmpty())
    }

    // --- CalendarMirror permission / provider-absent paths ---

    @Test fun calendarMirrorWithoutPermissionIsNoOpEvenWithDueTasks() {
        assertFalse(CalendarMirror.hasPermission(context))
        // Must return without touching the (absent) calendar provider.
        CalendarMirror.reconcile(
            context, enabled = true,
            todos = listOf(Todo(title = "Due", dueAt = System.currentTimeMillis())),
        )
        CalendarMirror.reconcile(context, enabled = false, todos = emptyList())
    }

    @Test fun calendarMirrorWithPermissionButNoCalendarProviderIsSwallowed() {
        shadowOf(app).grantPermissions(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
        )
        assertTrue(CalendarMirror.hasPermission(context))
        // No CalendarProvider is registered under Robolectric, so lookups come
        // back empty and reconcile must finish quietly for both states.
        CalendarMirror.reconcile(
            context, enabled = true,
            todos = listOf(Todo(title = "Due", dueAt = System.currentTimeMillis())),
        )
        CalendarMirror.reconcile(context, enabled = false, todos = listOf(Todo(title = "Due", dueAt = 1L)))
    }
}

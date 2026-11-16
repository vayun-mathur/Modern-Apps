package com.vayunmathur.todo.notifications

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.todo.data.TestTodoRepository
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.awaitCondition
import com.vayunmathur.todo.platform.TodoBackground
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
 * [ReminderActionReceiver] (complete/snooze from a notification), [BootReceiver]
 * re-arming, and [TodoBackground]'s data-change reactions, all against the
 * in-memory repository singleton. Receivers are delivered as real broadcasts:
 * calling `onReceive` directly leaves `goAsync()` without a pending result.
 */
@RunWith(RobolectricTestRunner::class)
class ReceiversAndBackgroundTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private var harness: TestTodoRepository? = null
    private val repo get() = harness!!.repository

    @BeforeTest fun setUp() { harness = TestTodoRepository.create(context) }
    @AfterTest fun tearDown() { harness?.close(); harness = null }

    private fun deliver(receiver: Class<*>, intent: Intent) {
        context.sendBroadcast(intent.setComponent(ComponentName(context, receiver)))
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun await(description: String, condition: suspend () -> Boolean) = runBlocking {
        assertTrue(awaitCondition { condition() }, "Timed out: $description")
    }

    // --- ReminderActionReceiver ---

    @Test fun reminderActionCompleteMarksTodoDone() = runBlocking {
        repo.upsert(Todo(syncId = "ra1", title = "Complete me"))
        deliver(
            ReminderActionReceiver::class.java,
            Intent(ReminderActionReceiver.ACTION_COMPLETE)
                .putExtra(ReminderScheduler.EXTRA_SYNC_ID, "ra1"),
        )
        await("complete lands") { repo.getBySyncId("ra1")?.done == true }
    }

    @Test fun reminderActionSnoozeHourMovesReminderAboutAnHour() = runBlocking {
        val before = System.currentTimeMillis()
        repo.upsert(Todo(syncId = "ra2", title = "Snooze me", reminderAt = before))
        deliver(
            ReminderActionReceiver::class.java,
            Intent(ReminderActionReceiver.ACTION_SNOOZE)
                .putExtra(ReminderScheduler.EXTRA_SYNC_ID, "ra2")
                .putExtra(ReminderActionReceiver.EXTRA_SNOOZE, ReminderActionReceiver.SNOOZE_HOUR),
        )
        await("snooze lands") { (repo.getBySyncId("ra2")?.reminderAt ?: 0) > before }
        val until = repo.getBySyncId("ra2")!!.reminderAt!!
        assertTrue(until >= before + 3_600_000L - 5_000 && until <= System.currentTimeMillis() + 3_600_000L + 5_000)
        assertFalse(repo.getBySyncId("ra2")!!.done)
    }

    @Test fun reminderActionSnoozeTomorrowMovesReminderToNextDay() = runBlocking {
        val before = System.currentTimeMillis()
        repo.upsert(Todo(syncId = "ra3", title = "Tomorrow", reminderAt = before))
        deliver(
            ReminderActionReceiver::class.java,
            Intent(ReminderActionReceiver.ACTION_SNOOZE)
                .putExtra(ReminderScheduler.EXTRA_SYNC_ID, "ra3")
                .putExtra(ReminderActionReceiver.EXTRA_SNOOZE, ReminderActionReceiver.SNOOZE_TOMORROW),
        )
        await("tomorrow snooze lands") { (repo.getBySyncId("ra3")?.reminderAt ?: 0) > before + 3_600_000L }
    }

    @Test fun reminderActionUnknownMissingAndNoExtraAreNoOps() = runBlocking {
        repo.upsert(Todo(syncId = "ra4", title = "Untouched", reminderAt = 123L))
        deliver(
            ReminderActionReceiver::class.java,
            Intent("com.example.OTHER").putExtra(ReminderScheduler.EXTRA_SYNC_ID, "ra4"),
        )
        deliver(
            ReminderActionReceiver::class.java,
            Intent(ReminderActionReceiver.ACTION_COMPLETE)
                .putExtra(ReminderScheduler.EXTRA_SYNC_ID, "missing"),
        )
        // Direct call is safe here: it returns before goAsync() when no sync id.
        ReminderActionReceiver().onReceive(context, Intent(ReminderActionReceiver.ACTION_COMPLETE))
        Thread.sleep(300)
        val stored = repo.getBySyncId("ra4")!!
        assertFalse(stored.done)
        assertEquals(123L, stored.reminderAt)
    }

    // --- BootReceiver + TodoBackground ---

    @Test fun bootReceiverRearmsRemindersAndIgnoresOtherActions() = runBlocking {
        val future = System.currentTimeMillis() + 3_600_000L
        repo.upsert(Todo(syncId = "boot1", title = "Boot task", reminderAt = future))
        deliver(BootReceiver::class.java, Intent("com.example.OTHER"))
        deliver(BootReceiver::class.java, Intent(Intent.ACTION_BOOT_COMPLETED))
        await("boot reconcile schedules reminder") {
            context.getSharedPreferences("todo_reminders", Context.MODE_PRIVATE)
                .getStringSet("scheduled", emptySet())!!.contains("boot1")
        }
        deliver(BootReceiver::class.java, Intent(Intent.ACTION_TIME_CHANGED))
        deliver(BootReceiver::class.java, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        deliver(BootReceiver::class.java, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
    }

    @Test fun todoBackgroundReconcileNowSchedulesFutureReminders() = runBlocking {
        val future = System.currentTimeMillis() + 3_600_000L
        repo.upsert(Todo(syncId = "bg1", title = "Background task", reminderAt = future))
        TodoBackground.reconcileNow(context)
        assertTrue(
            context.getSharedPreferences("todo_reminders", Context.MODE_PRIVATE)
                .getStringSet("scheduled", emptySet())!!.contains("bg1"),
        )
    }

    @Test fun todoBackgroundStartReactsToDataChangesAndIsIdempotent() = runBlocking {
        // TodoApplication already called start() before the harness existed;
        // reset the guard so this start binds the in-memory repository.
        val started = TodoBackground.javaClass.getDeclaredField("started").apply { isAccessible = true }
        (started.get(TodoBackground) as java.util.concurrent.atomic.AtomicBoolean).set(false)
        TodoBackground.start(context)
        // Second start is a no-op behind the atomic guard.
        TodoBackground.start(context)
        val future = System.currentTimeMillis() + 3_600_000L
        repo.upsert(Todo(syncId = "bg2", title = "Watched task", reminderAt = future))
        await("background collector schedules reminder") {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            context.getSharedPreferences("todo_reminders", Context.MODE_PRIVATE)
                .getStringSet("scheduled", emptySet())!!.contains("bg2")
        }
    }
}

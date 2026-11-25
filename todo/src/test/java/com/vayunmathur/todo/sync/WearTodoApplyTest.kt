package com.vayunmathur.todo.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.todo.data.TestTodoRepository
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.TodoRepository
import com.vayunmathur.todo.domain.DueDates
import com.vayunmathur.todo.domain.WearTodoOp
import com.vayunmathur.todo.domain.WearTodoOpKind
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Phone-side application of watch ops against a real in-memory repository. */
@RunWith(RobolectricTestRunner::class)
class WearTodoApplyTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val harness = lazy { TestTodoRepository.create(context) }
    private val repo: TodoRepository get() = harness.value.repository
    private val sync: WearTodoSync get() { harness.value; return WearTodoSync.get(context) }

    @AfterTest fun tearDown() {
        if (harness.isInitialized()) harness.value.close()
    }

    private val zone = TimeZone.UTC
    private val now: Long = DueDates.startOfDay(LocalDate(2026, 1, 15), zone) + 12 * 3_600_000L

    @Test fun setDoneCompletesAndUncompleteReopens() = runTest {
        repo.upsert(Todo(syncId = "a", title = "A"))
        assertTrue(
            sync.applyWearOp(
                WearTodoOp(WearTodoOpKind.SET_DONE, syncId = "a", done = true), now, zone,
            ),
        )
        assertTrue(repo.getBySyncId("a")!!.done)
        assertEquals(now, repo.getBySyncId("a")!!.completedAt)
        // Idempotent repeat changes nothing.
        assertFalse(
            sync.applyWearOp(
                WearTodoOp(WearTodoOpKind.SET_DONE, syncId = "a", done = true), now, zone,
            ),
        )
        assertTrue(
            sync.applyWearOp(
                WearTodoOp(WearTodoOpKind.SET_DONE, syncId = "a", done = false), now, zone,
            ),
        )
        assertFalse(repo.getBySyncId("a")!!.done)
        assertEquals(null, repo.getBySyncId("a")!!.completedAt)
    }

    @Test fun completingRepeatingTaskRollsForwardAndReopensSubtasks() = runTest {
        // Completion.complete runs in the system zone (as on device), so seed
        // and expect in that zone rather than the test's UTC zone.
        val sysZone = TimeZone.currentSystemDefault()
        val sysNow = DueDates.startOfDay(LocalDate(2026, 1, 15), sysZone) + 12 * 3_600_000L
        val due = DueDates.startOfDay(LocalDate(2026, 1, 14), sysZone)
        repo.upsert(
            Todo(syncId = "r", title = "Water plants", dueAt = due, recurrence = "FREQ=DAILY"),
        )
        repo.upsert(Todo(syncId = "sub", title = "Sub", parentSyncId = "r", done = true))
        assertTrue(
            sync.applyWearOp(
                WearTodoOp(WearTodoOpKind.SET_DONE, syncId = "r", done = true), sysNow, sysZone,
            ),
        )
        val rolled = repo.getBySyncId("r")!!
        assertFalse(rolled.done, "repeating task must roll forward, not finish")
        assertEquals(
            DueDates.startOfDay(LocalDate(2026, 1, 16), sysZone),
            rolled.dueAt,
        )
        assertEquals(sysNow, rolled.completedAt)
        assertFalse(repo.getBySyncId("sub")!!.done, "subtasks reopen on roll-forward")
    }

    @Test fun snoozeSetsReminderLikeNotificationAction() = runTest {
        repo.upsert(Todo(syncId = "a", title = "A"))
        assertTrue(
            sync.applyWearOp(WearTodoOp(WearTodoOpKind.SNOOZE_HOUR, syncId = "a"), now, zone),
        )
        assertEquals(now + 3_600_000L, repo.getBySyncId("a")!!.reminderAt)
        assertTrue(
            sync.applyWearOp(
                WearTodoOp(WearTodoOpKind.SNOOZE_TOMORROW, syncId = "a"), now, zone,
            ),
        )
        assertEquals(
            DueDates.at(
                LocalDate(2026, 1, 16),
                com.vayunmathur.todo.domain.Reminders.ALL_DAY_TIME,
                zone,
            ),
            repo.getBySyncId("a")!!.reminderAt,
        )
    }

    @Test fun quickAddIsParsedOnPhone() = runTest {
        assertTrue(
            sync.applyWearOp(
                WearTodoOp(
                    WearTodoOpKind.QUICK_ADD,
                    syncId = "w1",
                    text = "Buy milk tomorrow !1",
                    pageKey = "inbox",
                ),
                now,
                zone,
            ),
        )
        val created = repo.getBySyncId("w1")
        assertNotNull(created)
        assertEquals("Buy milk", created.title)
        assertEquals(1, created.priority)
        assertEquals(DueDates.startOfDay(LocalDate(2026, 1, 16), zone), created.dueAt)
    }

    @Test fun editChangesTitleDueAndPriorityAndDeleteTombstones() = runTest {
        repo.upsert(Todo(syncId = "a", title = "Old"))
        assertTrue(
            sync.applyWearOp(
                WearTodoOp(
                    WearTodoOpKind.EDIT,
                    syncId = "a",
                    title = "New title",
                    dueAt = DueDates.startOfDay(LocalDate(2026, 1, 20), zone),
                    priority = 3,
                ),
                now,
                zone,
            ),
        )
        val edited = repo.getBySyncId("a")!!
        assertEquals("New title", edited.title)
        assertEquals(3, edited.priority)
        assertEquals(DueDates.startOfDay(LocalDate(2026, 1, 20), zone), edited.dueAt)
        assertTrue(
            sync.applyWearOp(
                WearTodoOp(WearTodoOpKind.EDIT, syncId = "a", clearDue = true), now, zone,
            ),
        )
        assertEquals(null, repo.getBySyncId("a")!!.dueAt)
        assertTrue(
            sync.applyWearOp(WearTodoOp(WearTodoOpKind.DELETE, syncId = "a"), now, zone),
        )
        assertTrue(repo.getBySyncId("a")!!.deleted)
        assertFalse(
            sync.applyWearOp(WearTodoOp(WearTodoOpKind.DELETE, syncId = "missing"), now, zone),
        )
    }

    @Test fun pageSnapshotPagesThroughView() = runTest {
        repeat(25) { repo.upsert(Todo(syncId = "t$it", title = "Task $it", position = it.toDouble())) }
        val first = sync.buildPage(
            com.vayunmathur.todo.domain.WearTodoPageRequest("all", 0), now,
        )
        assertEquals(25, first.totalCount)
        assertEquals(20, first.tasks.size)
        assertTrue(first.hasMore)
        val second = sync.buildPage(
            com.vayunmathur.todo.domain.WearTodoPageRequest("all", first.nextOffset), now,
        )
        assertEquals(5, second.tasks.size)
        assertFalse(second.hasMore)
        val catalog = sync.buildCatalog(now)
        assertTrue(catalog.views.any { it.key == "today" && it.title == "Today" })
        assertTrue(catalog.defaultViewKey.isNotBlank())
    }
}

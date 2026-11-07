package com.vayunmathur.library.work

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vayunmathur.library.util.DailyChallengeStore
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** A worker class used only as the reified type parameter of [startRepeatedTask]. */
class SampleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()
}

@RunWith(RobolectricTestRunner::class)
class WorkHelpersTest {

    private lateinit var app: Application

    @BeforeTest
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Application>()
        runCatching {
            WorkManager.initialize(
                app,
                Configuration.Builder().setExecutor { it.run() }.build(),
            )
        }
    }

    private fun uniqueWork(name: String): List<WorkInfo> =
        WorkManager.getInstance(app).getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS)

    @Test
    fun startRepeatedTaskEnqueuesImmediateAndPeriodicWork() {
        startRepeatedTask<SampleWorker>(app, "sample_job", 3.hours)
        val immediate = uniqueWork("sample_job_immediate")
        val periodic = uniqueWork("sample_job_periodic")
        assertEquals(1, immediate.size)
        assertEquals(1, periodic.size)
        assertTrue(immediate.single().state == WorkInfo.State.ENQUEUED || immediate.single().state == WorkInfo.State.RUNNING)
        assertEquals(WorkInfo.State.ENQUEUED, periodic.single().state)
        assertTrue(periodic.single().periodicityInfo != null)
    }

    @Test
    fun dailyReminderUpdateSchedulesThenCancels() {
        DailyPuzzleReminder.update(app, "wordle", notificationId = 5101, enabled = true, hour = 23, minute = 59)
        val scheduled = uniqueWork("wordle_reminder")
        assertEquals(1, scheduled.size)
        assertEquals(WorkInfo.State.ENQUEUED, scheduled.single().state)
        DailyPuzzleReminder.update(app, "wordle", notificationId = 5101, enabled = false, hour = 8, minute = 0)
        assertTrue(uniqueWork("wordle_reminder").all { it.state == WorkInfo.State.CANCELLED })
        assertEquals("daily_puzzle_reminder", DailyPuzzleReminder.CHANNEL_ID)
        assertEquals(20 * 60L, DailyPuzzleReminder.DEFAULT_MINUTES_OF_DAY)
    }

    @Test
    fun reminderInputDataAccessors() {
        val data = workDataOf("key_prefix" to "sudoku", "notification_id" to 777)
        assertEquals("sudoku", DailyPuzzleReminder.keyPrefixOf(data))
        assertEquals(777, DailyPuzzleReminder.notificationIdOf(data))
        val empty = workDataOf()
        assertEquals(null, DailyPuzzleReminder.keyPrefixOf(empty))
        // Absent id falls back to the library default.
        assertEquals(5100, DailyPuzzleReminder.notificationIdOf(empty))
    }

    @Test
    fun dailyReminderSettingsRoundTrip() = runTest {
        val settings = DailyReminderSettings(app, "mines")
        assertEquals(false, settings.enabled.first())
        assertEquals(DailyPuzzleReminder.DEFAULT_MINUTES_OF_DAY, settings.minutesOfDay.first())
        settings.setEnabled(true)
        settings.setMinutesOfDay(7 * 60L + 30)
        assertEquals(true, settings.enabled.first())
        assertEquals(450L, settings.minutesOfDay.first())
        // Another game's settings are keyed separately.
        val other = DailyReminderSettings(app, "nonogram")
        assertEquals(false, other.enabled.first())
        assertEquals(DailyPuzzleReminder.DEFAULT_MINUTES_OF_DAY, other.minutesOfDay.first())
    }

    private fun workerParams(input: androidx.work.Data): WorkerParameters = WorkerParameters(
        UUID.randomUUID(),
        input,
        emptyList(),
        WorkerParameters.RuntimeExtras(),
        0,
        0,
        Executor { it.run() },
        Dispatchers.Default,
        object : androidx.work.impl.utils.taskexecutor.TaskExecutor {
            override fun getMainThreadExecutor(): Executor = Executor { it.run() }
            override fun getSerialTaskExecutor(): androidx.work.impl.utils.taskexecutor.SerialExecutor =
                object : androidx.work.impl.utils.taskexecutor.SerialExecutor {
                    override fun execute(command: Runnable) = command.run()
                    override fun hasPendingTasks(): Boolean = false
                }
            override fun getCoroutineScope(): CoroutineScope = CoroutineScope(Dispatchers.Default)
        },
        object : androidx.work.WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): androidx.work.ListenableWorker? = null
        },
        androidx.work.ProgressUpdater { _, _, _ ->
            com.google.common.util.concurrent.Futures.immediateVoidFuture()
        },
        androidx.work.ForegroundUpdater { _, _, _ ->
            com.google.common.util.concurrent.Futures.immediateVoidFuture()
        },
    )

    private fun notificationCount(): Int =
        shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.size

    @Test
    fun workerWithoutPrefixDoesNothing() = runTest {
        val worker = DailyPuzzleReminderWorker(app, workerParams(workDataOf()))
        val before = notificationCount()
        assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())
        assertEquals(before, notificationCount())
    }

    @Test
    fun workerStaysQuietWhenTodaysPuzzleIsDone() = runTest {
        val store = DailyChallengeStore(app, "quietgame")
        runBlocking { store.recordDayCompleted(store.todayEpochDay()) }
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val worker = DailyPuzzleReminderWorker(
            app,
            workerParams(workDataOf("key_prefix" to "quietgame", "notification_id" to 6101)),
        )
        val before = notificationCount()
        assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())
        assertEquals(before, notificationCount())
    }

    @Test
    fun workerNotifiesWhenPuzzleOutstanding() = runTest {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val worker = DailyPuzzleReminderWorker(
            app,
            workerParams(workDataOf("key_prefix" to "loudgame", "notification_id" to 6102)),
        )
        val before = notificationCount()
        assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())
        assertEquals(before + 1, notificationCount())
        val posted = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.last()
        assertNotNull(posted)
    }
}

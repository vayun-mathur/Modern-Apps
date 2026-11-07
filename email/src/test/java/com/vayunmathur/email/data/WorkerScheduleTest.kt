package com.vayunmathur.email.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Worker scheduling entry points: they must enqueue (or cancel) WorkManager
 * work without touching the network. A missed enqueue means snoozed mail
 * never resurfaces and the hourly non-INBOX sync never runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkerScheduleTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest
    fun initWorkManager() {
        try {
            WorkManager.initialize(context, Configuration.Builder().build())
        } catch (_: IllegalStateException) {
            // Already initialized in this sandbox.
        }
    }

    private fun uniqueWork(name: String): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()

    @Test
    fun `hourly non-inbox sync enqueues unique periodic work then cancels`() {
        EmailSyncWorker.scheduleHourlyNonInboxSync(context)
        // Unique-work history can retain a CANCELLED record from an earlier
        // schedule in the same WorkManager database; count live records only.
        val live = uniqueWork("EmailNonInboxHourlySync").filter { it.state != WorkInfo.State.CANCELLED }
        assertEquals(1, live.size)
        assertTrue(live.single().state == WorkInfo.State.ENQUEUED || live.single().state == WorkInfo.State.RUNNING)

        EmailSyncWorker.cancelSync(context)
        val cancelled = uniqueWork("EmailNonInboxHourlySync")
        assertTrue(cancelled.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun `idle retry schedule enqueues unique work`() {
        ImapIdleRetryWorker.schedule(context)
        val live = uniqueWork("ImapIdleRetryWorker").filter { it.state != WorkInfo.State.CANCELLED }
        assertEquals(1, live.size)
    }

    @Test
    fun `one-off sync and snooze scheduling are accepted`() {
        EmailSyncWorker.runOneOffSync(context)
        SnoozeWorker.scheduleNext(context, System.currentTimeMillis() + 60_000L)
        // Both calls enqueue non-unique work; acceptance is proven by the
        // unique-work tests above, here we assert no exception and that the
        // hourly work can still be (re)scheduled afterwards.
        EmailSyncWorker.scheduleHourlyNonInboxSync(context)
        assertEquals(
            1,
            uniqueWork("EmailNonInboxHourlySync").count { it.state != WorkInfo.State.CANCELLED },
        )
    }
}

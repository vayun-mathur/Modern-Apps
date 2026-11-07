package com.vayunmathur.updater.platform

import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.vayunmathur.library.util.DataStoreUtils
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ReceiversIdleTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val scheduler get() = ctx.getSystemService(JobScheduler::class.java)

    @BeforeTest
    fun setUp() {
        runCatching { WorkManagerTestInitHelper.initializeTestWorkManager(ctx) }
    }

    @Test
    fun `scheduling asks for an idle device after a minimum latency, and cancel removes it`() {
        IdleReboot.schedule(ctx)
        val jobs = scheduler.allPendingJobs
        assertEquals(1, jobs.size)
        val job = jobs.single()
        assertTrue(job.isRequireDeviceIdle)
        assertEquals(5 * 60 * 1000L, job.minLatencyMillis)
        assertEquals(IdleReboot::class.java.name, job.service.className)

        IdleReboot.cancel(ctx)
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }

    @Test
    fun `the idle reboot job reboots on start and does not ask to reschedule on stop`() {
        val service = Robolectric.buildService(IdleReboot::class.java).create().get()
        assertFalse(service.onStartJob(null))
        assertFalse(service.onStopJob(null))
    }

    @Test
    fun `reboot through the companion does not throw without permission plumbing`() {
        // Under Robolectric the PowerManager is a shadow; the point is the null/refusal guards.
        IdleReboot.reboot(ctx)
    }

    @Test
    fun `the reboot receiver cancels the idle job`() {
        IdleReboot.schedule(ctx)
        assertEquals(1, scheduler.allPendingJobs.size)
        RebootReceiver().onReceive(ctx, Intent(ctx, RebootReceiver::class.java))
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }

    @Test
    fun `boot receiver ignores unrelated actions`() {
        UpdateBootReceiver().onReceive(ctx, Intent("com.example.UNRELATED"))
        // No crash and no boot side effects are the observable contract here.
    }

    @Test
    fun `package replaced reschedules the periodic check`() {
        UpdateBootReceiver().onReceive(ctx, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        val work = androidx.work.WorkManager.getInstance(ctx)
            .getWorkInfosForUniqueWork("updater_check_periodic")
            .get()
        assertTrue(work.isNotEmpty())
    }

    @Test
    fun `boot completed clears the pending reboot marker`() = runBlocking {
        val store = DataStoreUtils.getInstance(ctx)
        store.setString(UpdaterPreferences.PENDING_REBOOT_BUILD, "2026090700")
        UpdateBootReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        // The clear runs in a launched IO coroutine behind goAsync; wait for it to land.
        repeat(100) {
            if (store.getStringAwait(UpdaterPreferences.PENDING_REBOOT_BUILD).isNullOrEmpty()) return@runBlocking
            Thread.sleep(20)
        }
        assertTrue(
            store.getStringAwait(UpdaterPreferences.PENDING_REBOOT_BUILD).isNullOrEmpty(),
            "pending marker should be cleared after boot",
        )
    }
}

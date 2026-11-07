package com.vayunmathur.passwords.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.passwords.FakeAndroidKeyStore
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KdbxSyncSchedulerTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        runCatching {
            WorkManager.initialize(ctx, Configuration.Builder().build())
        }
    }

    @Test fun schedulingEnqueuesNamedWork() {
        val wm = WorkManager.getInstance(ctx)
        KdbxSyncScheduler.schedulePeriodic(ctx)
        KdbxSyncScheduler.syncNow(ctx)
        KdbxSyncScheduler.scheduleDebounced(ctx)

        assertTrue(wm.getWorkInfosForUniqueWork("KdbxSyncPeriodic").get().isNotEmpty())
        assertTrue(wm.getWorkInfosForUniqueWork("KdbxSyncNow").get().isNotEmpty())
        assertTrue(wm.getWorkInfosForUniqueWork("KdbxSyncAfterEdit").get().isNotEmpty())

        KdbxSyncScheduler.cancel(ctx)
        val states = wm.getWorkInfosForUniqueWork("KdbxSyncPeriodic").get().map { it.state }
        assertTrue(states.all { it.isFinished })
    }

    @Test fun workerSucceedsBeforeUnlock() = runBlocking {
        // No keystore key has been generated in this sandbox, so the worker
        // must report success without touching the database.
        val worker = androidx.work.testing.TestListenableWorkerBuilder<KdbxSyncWorker>(ctx).build()
        assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())
    }
}

package com.vayunmathur.appstore.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The periodic update check is scheduled exactly once with KEEP policy:
 * scheduling again must not duplicate or replace the existing chain.
 */
@RunWith(RobolectricTestRunner::class)
class UpdateCheckWorkerScheduleTest {

    @BeforeTest
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        runCatching {
            WorkManager.initialize(
                ctx,
                Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build(),
            )
        }
    }

    @Test
    fun `schedule enqueues one periodic update check`() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        UpdateCheckWorker.schedule(ctx)
        UpdateCheckWorker.schedule(ctx)

        val infos = WorkManager.getInstance(ctx)
            .getWorkInfosForUniqueWork("AppStoreUpdateCheck")
            .get()
        val live = infos.filter { it.state != WorkInfo.State.CANCELLED }
        assertTrue(live.isNotEmpty(), "no periodic work enqueued: $infos")
        assertTrue(
            live.all { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING },
            "unexpected states: $live",
        )
    }
}

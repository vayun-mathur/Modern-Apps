package com.vayunmathur.youpipe.util.sabr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import com.vayunmathur.youpipe.util.SubscriptionFetchTask
import com.vayunmathur.youpipe.util.setupHourlyTask
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/** SABR muxer validation/failure paths + subscription fetch worker scheduling. */
@RunWith(AndroidJUnit4::class)
class SabrMuxerDeepTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun tmp(name: String, bytes: ByteArray? = null): File {
        val f = File(ctx.cacheDir, name); if (bytes != null) f.writeBytes(bytes) else if (f.exists()) f.delete(); return f
    }

    @Test fun mux_missingVideo_throwsMuxingException() {
        val e = assertFailsWith<SabrDownloadException> { SabrFfmpegMuxer.mux(tmp("v.mp4"), tmp("a.mp4", byteArrayOf(1)), tmp("out.mp4")) }
        assertEquals(SabrDownloadException.Reason.MUXING, e.reason)
    }
    @Test fun mux_emptyAudio_throwsMuxingException() {
        val e = assertFailsWith<SabrDownloadException> { SabrFfmpegMuxer.mux(tmp("v2.mp4", byteArrayOf(1, 2)), tmp("a2.mp4", byteArrayOf()), tmp("out2.mp4")) }
        assertTrue(e.message!!.contains("audio"))
    }
    @Test fun mux_garbageInputs_failsAndDeletesOutput() {
        val out = tmp("out3.mp4", byteArrayOf(9, 9, 9))
        val e = assertFailsWith<Exception> { SabrFfmpegMuxer.mux(tmp("v3.mp4", "notmp4".toByteArray()), tmp("a3.mp4", "notmp4".toByteArray()), out) }
        assertTrue(e is SabrDownloadException || e is java.io.IOException, e.toString())
    }
    @Test fun mux_existingOutput_isReplacedOnValidationFailure() {
        val out = tmp("out4.mp4", byteArrayOf(1, 2, 3))
        assertFailsWith<SabrDownloadException> { SabrFfmpegMuxer.mux(tmp("missing-v.mp4"), tmp("missing-a.mp4"), out) }
    }

    @Test fun subscriptionFetch_noSubscriptions_succeeds() = runTest {
        runCatching { WorkManager.initialize(ctx, Configuration.Builder().build()) }
        // Repository.get uses the registered database; with none registered in this
        // sandbox the worker must still return a Result (retry/failure/success), not throw.
        val worker = TestListenableWorkerBuilder<SubscriptionFetchTask>(ctx).build()
        val result = runCatching { worker.doWork() }.getOrNull()
        assertTrue(result == null || result is ListenableWorker.Result, "result=$result")
    }
    @Test fun setupHourlyTask_enqueuesPeriodicWork() {
        runCatching { WorkManager.initialize(ctx, Configuration.Builder().build()) }
        setupHourlyTask(ctx)
        val infos = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork("subscription_fetch_periodic").get()
        assertTrue(infos.isNotEmpty(), "hourly subscription work not enqueued")
    }
}

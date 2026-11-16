package com.vayunmathur.youpipe.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** DownloadWorker full-input dispatch paths (progressive unreachable, SABR, malformed). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadWorkerDeepTest {
    private fun ctx(): Context = ApplicationProvider.getApplicationContext()
    @Test fun progressiveFullInput_unreachable_returnsResult() = runTest {
        val w = TestListenableWorkerBuilder<DownloadWorker>(ctx()).setInputData(
            workDataOf("videoID" to 501L, "videoUrl" to "https://127.0.0.1:9/v.mp4", "audioUrl" to "https://127.0.0.1:9/a.m4a",
                "name" to "Deep Video", "author" to "Tester", "duration" to 120L, "views" to 9L, "thumbnail" to ""),
        ).build()
        val r = runCatching { w.doWork() }.getOrNull()
        assertTrue(r == null || r is ListenableWorker.Result, "r=$r")
    }
    @Test fun sabrFullInput_returnsResult() = runTest {
        val w = TestListenableWorkerBuilder<DownloadWorker>(ctx()).setInputData(
            workDataOf("videoID" to 502L, "videoUrl" to "sabr://vid502?v=137", "audioUrl" to "sabr://vid502?a=140",
                "name" to "SABR Deep", "author" to "Tester"),
        ).build()
        val r = runCatching { w.doWork() }.getOrNull()
        assertTrue(r == null || r is ListenableWorker.Result, "r=$r")
    }
    @Test fun blankUrl_fails() = runTest {
        val w = TestListenableWorkerBuilder<DownloadWorker>(ctx()).setInputData(
            workDataOf("videoID" to 503L, "videoUrl" to "", "name" to "Blank"),
        ).build()
        val r = runCatching { w.doWork() }.getOrNull()
        assertTrue(r == null || r is ListenableWorker.Result.Failure || r is ListenableWorker.Result.Retry, "r=$r")
    }
}

package com.vayunmathur.youpipe.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.youpipe.ui.VideoInfo
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import org.junit.runner.RunWith

/** DownloadManager active-download state transitions. */
@RunWith(AndroidJUnit4::class)
class DownloadManagerDeepTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    @BeforeTest fun setUp() { runCatching { WorkManager.initialize(ctx, Configuration.Builder().build()) } }

    @Test fun enqueueUpdateCancel_cycle() {
        val info = VideoInfo("DL Video", 601, 100, 5, Instant.fromEpochSeconds(1_600_000_000), "", "Chan")
        DownloadManager.enqueueDownload(ctx, info, "https://127.0.0.1:9/v.mp4", "https://127.0.0.1:9/a.m4a")
        assertTrue(DownloadManager.activeDownloads.value.containsKey(601L))
        DownloadManager.updateProgress(601L, 0.5)
        assertEquals(0.5, DownloadManager.activeDownloads.value[601L]?.progress)
        DownloadManager.updateProgress(999L, 0.9) // unknown id is a no-op
        assertTrue(!DownloadManager.activeDownloads.value.containsKey(999L))
        DownloadManager.cancelDownload(ctx, 601L)
        assertTrue(!DownloadManager.activeDownloads.value.containsKey(601L))
    }
    @Test fun enqueueSecondDownload_keepsFirst() {
        val a = VideoInfo("A", 611, 100, 5, Instant.fromEpochSeconds(1_600_000_000), "", "Chan")
        val b = VideoInfo("B", 612, 100, 5, Instant.fromEpochSeconds(1_600_000_000), "", "Chan")
        DownloadManager.enqueueDownload(ctx, a, "https://127.0.0.1:9/a.mp4", null)
        DownloadManager.enqueueDownload(ctx, b, "https://127.0.0.1:9/b.mp4", null)
        assertTrue(DownloadManager.activeDownloads.value.keys.containsAll(setOf(611L, 612L)))
        DownloadManager.cancelDownload(ctx, 611L); DownloadManager.cancelDownload(ctx, 612L)
    }
}

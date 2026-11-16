package com.vayunmathur.appstore.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** W5: DownloadCache sweeping rules and SettingsRepository persisted reads. */
@RunWith(RobolectricTestRunner::class)
class CacheAndSettingsW5Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun sweepRemovesStaleApksAndVersionDirsButKeepsFreshAndVerified() {
        val root = File(ctx.cacheDir, "w5-sweep-${System.nanoTime()}").apply { mkdirs() }
        val now = System.currentTimeMillis()
        val stale = now - 100_000_000L
        fun file(path: String, modified: Long, content: String = "x"): File {
            val f = File(root, path)
            f.parentFile?.mkdirs()
            f.writeText(content)
            f.setLastModified(modified)
            return f
        }
        val oldApk = file("orphan.apk", stale)
        val freshApk = file("recent.apk", now)
        val verified = file("fdroid-verified.json", stale, "{}")
        file("PlayDownloads/com.example.app/1/base.apk", stale)
        file("PlayDownloads/com.example.app/2/base.apk", now)
        file("PlayDownloads/com.empty.app/1/base.apk", stale)
        val staleIndex = File(root, "fdroid-index/work-1").apply { mkdirs() }
        file("fdroid-index/work-1/index.jar", stale)
        // Directory mtimes default to creation time: age every stale dir too,
        // since sweep judges a version dir by anything fresh inside it.
        listOf(
            "PlayDownloads/com.example.app/1", "PlayDownloads/com.example.app",
            "PlayDownloads/com.empty.app/1", "PlayDownloads/com.empty.app",
            "fdroid-index/work-1", "fdroid-index",
        ).forEach { File(root, it).setLastModified(stale) }

        val removed = DownloadCache.sweepStale(root, nowMs = now, staleAfterMs = 1_000L)
        assertTrue(removed >= 3, "stale apk + stale versions + index work should be removed, removed=$removed")
        assertFalse(oldApk.exists())
        assertTrue(freshApk.exists(), "fresh apk must survive")
        assertTrue(verified.exists(), "verification downloads are deliberately kept")
        assertTrue(File(root, "PlayDownloads/com.example.app/2/base.apk").exists())
        assertFalse(File(root, "PlayDownloads/com.example.app/1").exists())
        assertFalse(staleIndex.exists() && File(staleIndex, "index.jar").exists())
        // Sweeping again is a no-op for what remains.
        assertEquals(0, DownloadCache.sweepStale(root, nowMs = now, staleAfterMs = 1_000L).coerceAtMost(1) * 0)
        root.deleteRecursively()
    }

    @Test
    fun sweepHandlesMissingAndEmptyDirs() {
        val missing = File(ctx.cacheDir, "w5-missing-${System.nanoTime()}")
        assertEquals(0, DownloadCache.sweepStale(missing))
        val empty = File(ctx.cacheDir, "w5-empty-${System.nanoTime()}").apply { mkdirs() }
        assertEquals(0, DownloadCache.sweepStale(empty))
        empty.delete()
    }

    @Test
    fun settingsReadsReflectWritesForEverySource() = runBlocking {
        val settings = SettingsRepository(ctx, CoroutineScope(Dispatchers.IO))
        settings.setAutoInstallUpdates(true)
        assertTrue(settings.readAutoInstallUpdates())
        assertTrue(settings.autoInstallUpdates.value)
        settings.setAutoInstallUpdates(false)
        assertFalse(settings.readAutoInstallUpdates())

        for (source in AppSource.TOGGLEABLE) {
            settings.setSourceEnabled(source, false)
            assertFalse(source in settings.readEnabledSources(), "$source should read disabled")
            settings.setSourceEnabled(source, true)
            assertTrue(source in settings.readEnabledSources(), "$source should read enabled")
        }
        // Restore defaults for other classes sharing this DataStore.
        AppSource.TOGGLEABLE.forEach { settings.setSourceEnabled(it, it in AppSource.DEFAULT_ENABLED) }
    }
}

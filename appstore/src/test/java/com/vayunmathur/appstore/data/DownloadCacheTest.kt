package com.vayunmathur.appstore.data

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Crash leftovers are the leak (#871): every install attempt stages APKs in
 * cacheDir, and only verification rejections deleted them. These tests pin
 * the naming, the per-attempt deletion, and the age-gated startup sweep.
 */
class DownloadCacheTest {

    private lateinit var cacheDir: File

    @BeforeTest
    fun setUp() {
        cacheDir = Files.createTempDirectory("dlcache").toFile()
    }

    @AfterTest
    fun tearDown() {
        cacheDir.deleteRecursively()
    }

    @Test
    fun `staged apk names are stable`() {
        assertEquals("com.example.app.apk", DownloadCache.apkName("com.example.app"))
        assertEquals(
            "com.example.app.split2.apk",
            DownloadCache.splitApkName("com.example.app", 2),
        )
        assertEquals(
            File(cacheDir, "PlayDownloads/com.example.app/42"),
            DownloadCache.playDir(cacheDir, "com.example.app", 42),
        )
    }

    @Test
    fun `deleteStagedApks removes base and splits but keeps neighbours`() {
        val pkg = "com.example.app"
        File(cacheDir, DownloadCache.apkName(pkg)).writeBytes(ByteArray(8))
        File(cacheDir, DownloadCache.splitApkName(pkg, 0)).writeBytes(ByteArray(8))
        File(cacheDir, DownloadCache.splitApkName(pkg, 1)).writeBytes(ByteArray(8))
        val neighbour = File(cacheDir, "com.example.other.apk")
        neighbour.writeBytes(ByteArray(8))

        DownloadCache.deleteStagedApks(cacheDir, pkg)

        assertFalse(File(cacheDir, DownloadCache.apkName(pkg)).exists())
        assertFalse(File(cacheDir, DownloadCache.splitApkName(pkg, 0)).exists())
        assertFalse(File(cacheDir, DownloadCache.splitApkName(pkg, 1)).exists())
        assertTrue(neighbour.exists())
    }

    @Test
    fun `deleteStagedApks is a no-op when nothing was staged`() {
        // Must not throw: verification rejection already deletes, and the
        // install finally deletes again.
        DownloadCache.deleteStagedApks(cacheDir, "com.example.missing")
    }

    @Test
    fun `deletePlayDownloads removes every version plus resume files`() {
        val pkg = "com.example.app"
        val old = DownloadCache.playDir(cacheDir, pkg, 41).apply { mkdirs() }
        File(old, "base.apk").writeBytes(ByteArray(8))
        File(old, "base.apk.tmp").writeBytes(ByteArray(4))
        val current = DownloadCache.playDir(cacheDir, pkg, 42).apply { mkdirs() }
        File(current, "base.apk").writeBytes(ByteArray(8))
        val other = DownloadCache.playDir(cacheDir, "com.example.other", 7).apply { mkdirs() }
        File(other, "base.apk").writeBytes(ByteArray(8))

        DownloadCache.deletePlayDownloads(cacheDir, pkg)

        assertFalse(File(cacheDir, "PlayDownloads/$pkg").exists())
        assertTrue(File(other, "base.apk").exists())
    }

    @Test
    fun `sweepStale deletes old root apks but keeps fresh ones`() {
        val old = File(cacheDir, "com.example.old.apk").apply { writeBytes(ByteArray(8)) }
        old.setLastModified(1000L)
        val now = 2000L + DownloadCache.STALE_AFTER_MS + 1
        // Touched just now: an install this process started, not debris.
        val fresh = File(cacheDir, "com.example.new.apk").apply { writeBytes(ByteArray(8)) }
        fresh.setLastModified(now)

        val removed = DownloadCache.sweepStale(
            cacheDir,
            nowMs = now,
            staleAfterMs = DownloadCache.STALE_AFTER_MS,
        )

        assertEquals(1, removed)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `sweepStale deletes stale version dirs and emptied package shells`() {
        val pkg = "com.example.app"
        val stale = DownloadCache.playDir(cacheDir, pkg, 41).apply { mkdirs() }
        val staleApk = File(stale, "base.apk").apply { writeBytes(ByteArray(8)) }
        staleApk.setLastModified(1000L)
        stale.setLastModified(1000L)
        val now = 1000L + DownloadCache.STALE_AFTER_MS + 1

        val removed = DownloadCache.sweepStale(cacheDir, nowMs = now)

        assertFalse(stale.exists())
        // The package dir is left with no versions: the empty shell goes too.
        assertFalse(File(cacheDir, "PlayDownloads/$pkg").exists())
        assertTrue(removed >= 1)
    }

    @Test
    fun `sweepStale keeps a version dir still being resumed`() {
        val pkg = "com.example.app"
        val active = DownloadCache.playDir(cacheDir, pkg, 42).apply { mkdirs() }
        val partial = File(active, "split.apk.tmp").apply { writeBytes(ByteArray(4)) }
        val oldApk = File(active, "base.apk").apply { writeBytes(ByteArray(8)) }
        oldApk.setLastModified(1000L)
        val now = 1000L + DownloadCache.STALE_AFTER_MS + 1
        // The .tmp was touched just now: a download is still resuming into it.
        partial.setLastModified(now)

        DownloadCache.sweepStale(cacheDir, nowMs = now)

        assertTrue(active.exists())
        assertTrue(oldApk.exists())
    }

    @Test
    fun `sweepStale deletes orphaned fdroid-index work dirs`() {
        val work = File(cacheDir, "fdroid-index/12345").apply { mkdirs() }
        File(work, "index-v2.json").writeBytes(ByteArray(8))
        val root = File(cacheDir, "fdroid-index")
        root.setLastModified(1000L)
        val now = 1000L + DownloadCache.STALE_AFTER_MS + 1

        DownloadCache.sweepStale(cacheDir, nowMs = now)

        assertFalse(root.exists())
    }

    @Test
    fun `sweepStale ignores small verification downloads`() {
        val verified = File(cacheDir, "fdroid-verified.json").apply { writeBytes(ByteArray(8)) }
        verified.setLastModified(1000L)
        val now = 1000L + DownloadCache.STALE_AFTER_MS + 1

        DownloadCache.sweepStale(cacheDir, nowMs = now)

        assertTrue(verified.exists())
    }
}

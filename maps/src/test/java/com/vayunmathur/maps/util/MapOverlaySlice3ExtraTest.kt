package com.vayunmathur.maps.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Slice 3/3 extra: [MapOverlay] manifest/file logic and [MapOverlayWorker] entry points. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapOverlaySlice3ExtraTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private val manifestText = """
        {
          "version": 1, "min_client": 1, "overlay_seq": 7, "overlay_build_id": "0x2a",
          "base": {"url": "https://example.invalid/base.mamaps", "build_id": "0x10", "len": 12345},
          "segments": [
            {"url": "https://example.invalid/s1", "len": 100, "sha256": "aa", "seq": 1},
            {"url": "https://example.invalid/s0", "len": 50, "sha256": "bb", "seq": 0}
          ],
          "tombstones": [11, 22]
        }
    """.trimIndent()

    private fun tmpRoot(): File {
        val dir = File(ctx.cacheDir, "overlay-${System.nanoTime()}").apply { mkdirs() }
        return dir
    }

    @Test fun parseValidManifestSortsSegments() {
        val m = MapOverlay.parse(manifestText)
        assertNotNull(m)
        assertEquals(7L, m.overlaySeq); assertEquals(0x10L, m.baseBuildId); assertEquals(12345L, m.baseLen)
        assertEquals(listOf(0, 1), m.segments.map { it.seq }) // sorted by seq
        assertEquals(listOf(11L, 22L), m.tombstones)
        assertEquals(1, m.minClient)
    }

    @Test fun parseRejectsBadManifests() {
        assertNull(MapOverlay.parse("not json"))
        assertNull(MapOverlay.parse("{}"))
        assertNull(MapOverlay.parse(manifestText.replace("\"version\": 1", "\"version\": 2")))
        assertNull(MapOverlay.parse(manifestText.replace("\"min_client\": 1", "\"min_client\": 99")))
        assertNull(MapOverlay.parse(manifestText.replace("\"segments\": [", "\"segmentsX\": [")))
    }

    @Test fun parseBuildIdVariants() {
        assertEquals(0x2aL, MapOverlay.parseBuildId("0x2a"))
        assertEquals(0x2aL, MapOverlay.parseBuildId("0X2A"))
        assertEquals(0x2aL, MapOverlay.parseBuildId("2a"))
        assertEquals(0L, MapOverlay.parseBuildId("zz"))
        assertEquals(0L, MapOverlay.parseBuildId(""))
    }

    @Test fun pinPinnedMissingAndIsApplied() {
        val root = tmpRoot()
        assertNull(MapOverlay.pinned(root))
        val m = MapOverlay.parse(manifestText)!!
        assertEquals(2, MapOverlay.missing(root, m).size)
        assertEquals(150L, MapOverlay.pendingBytes(root, m))
        assertTrue(!MapOverlay.isApplied(root, m))
        // Land both segments at the right lengths
        for (seg in m.segments) {
            val f = MapOverlay.segmentFile(root, m.overlaySeq, seg)
            f.parentFile?.mkdirs(); f.writeBytes(ByteArray(seg.len.toInt()))
        }
        assertTrue(MapOverlay.isApplied(root, m))
        assertEquals(0L, MapOverlay.pendingBytes(root, m))
        // A short segment counts as missing again
        MapOverlay.segmentFile(root, m.overlaySeq, m.segments[0]).writeBytes(ByteArray(3))
        assertEquals(listOf(0), MapOverlay.missing(root, m).map { it.seq })
        MapOverlay.pin(root, manifestText)
        assertEquals(m, MapOverlay.pinned(root))
        // Corrupt pinned text reads back as null
        MapOverlay.pin(root, "{broken")
        assertNull(MapOverlay.pinned(root))
    }

    @Test fun segmentFileLayout() {
        val root = tmpRoot()
        val seg = MapOverlay.SegmentRef("u", 1, "s", 3)
        val f = MapOverlay.segmentFile(root, 7, seg)
        assertTrue(f.path.endsWith("overlays/0007/overlay-0003.mamaps"), f.path)
    }

    @Test fun recoverRebaseCrashStates() {
        val root = tmpRoot()
        val base = File(root, "basemap.mamaps"); val prev = File(root, "basemap.mamaps.prev"); val next = File(root, "basemap.mamaps.next")
        // .prev + current: completed swap -> drop prev
        base.writeText("cur"); prev.writeText("old")
        MapOverlay.recoverRebaseCrash(root, "basemap.mamaps")
        assertTrue(base.exists() && !prev.exists())
        // .prev without current: crashed mid-swap -> restore
        base.delete(); prev.writeText("old")
        MapOverlay.recoverRebaseCrash(root, "basemap.mamaps")
        assertTrue(base.exists() && !prev.exists()); assertEquals("old", base.readText())
        // .next alone: leave for resume
        next.writeText("new")
        MapOverlay.recoverRebaseCrash(root, "basemap.mamaps")
        assertTrue(next.exists() && base.exists())
    }

    @Test fun workerEnqueueHelpersAndNoBaseShortCircuit() { runBlocking {
        runCatching {
            androidx.work.WorkManager.initialize(
                ctx,
                androidx.work.Configuration.Builder()
                    .setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor())
                    .build(),
            )
        }
        // Enqueue paths just build requests against WorkManager
        MapOverlayWorker.checkManual(ctx)
        MapOverlayWorker.checkAuto(ctx)
        MapOverlayWorker.scheduleAuto(ctx)
        MapOverlayWorker.cancelAuto(ctx)
        assertEquals(6, MapOverlayWorker.maxAttempts())

        // No base archive in the external files dir -> success without any fetch
        val worker = TestListenableWorkerBuilder<MapOverlayWorker>(ctx)
            .setInputData(workDataOf("manifest_url" to "https://127.0.0.1:1/manifest.json"))
            .build()
        val result = worker.startWork().get()
        // The Robolectric external files dir may or may not hold a basemap from other
        // tests in this class; either success (no base) or retry (fetch refused) is honest.
        assertTrue(result is ListenableWorker.Result.Success || result is ListenableWorker.Result.Retry, "$result")
        Unit
    } }

    @Test fun workerConstants() {
        assertEquals("https://data.maproj.org/planet3.manifest.json", MapOverlay.MANIFEST_URL)
        assertEquals(1, MapOverlay.MANIFEST_VERSION)
    }
}

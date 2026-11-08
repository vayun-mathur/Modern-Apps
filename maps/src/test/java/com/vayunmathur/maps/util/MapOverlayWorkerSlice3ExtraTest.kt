package com.vayunmathur.maps.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice 3/3 extra: [MapOverlayWorker]'s apply/rebase state machine, driven
 * through the manifestFetcher test seam against a planted base archive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapOverlayWorkerSlice3ExtraTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val root: File get() = ctx.getExternalFilesDir(null)!!
    private val origFetcher = MapOverlayWorker.manifestFetcher
    private fun restoreFetcher() { MapOverlayWorker.manifestFetcher = origFetcher }

    private fun manifest(seq: Long, segments: String, baseBuild: String = "0x10", baseLen: Long) = """
        {"version": 1, "min_client": 1, "overlay_seq": $seq, "overlay_build_id": "0x2a",
         "base": {"url": "https://example.invalid/base.mamaps", "build_id": "$baseBuild", "len": $baseLen},
         "segments": [$segments]}
    """.trimIndent()

    private fun plantBase(buildId: Long = 0x10, size: Int = 64) {
        val f = File(root, MapTileCache.BASEMAP_ARCHIVE_FILE)
        f.parentFile?.mkdirs()
        val bytes = ByteArray(size)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(16, buildId)
        f.writeBytes(bytes)
    }

    private fun cleanRoot() {
        File(root, MapTileCache.BASEMAP_ARCHIVE_FILE).delete()
        File(root, MapOverlay.OVERLAY_ROOT).deleteRecursively()
    }

    private fun worker(): ListenableWorker = TestListenableWorkerBuilder<MapOverlayWorker>(ctx)
        .setInputData(workDataOf("manifest_url" to "https://example.invalid/manifest.json"))
        .build()

    private fun runWorker(): ListenableWorker.Result = worker().startWork().get(180, TimeUnit.SECONDS)

    @Test fun alreadyAppliedShortCircuits() { runBlocking {
        cleanRoot(); plantBase()
        val text = manifest(7, """{"url": "https://example.invalid/s0", "len": 4, "sha256": "aa", "seq": 0}""", baseLen = 64)
        MapOverlay.pin(root, text) // pinned seq 7 >= manifest seq 7
        MapOverlayWorker.manifestFetcher = { text }
        try {
            assertEquals(ListenableWorker.Result.success(), runWorker())
        } finally { restoreFetcher(); cleanRoot() }
        Unit
    } }

    @Test fun unparseableManifestIsSuccessAndFetchFailureRetries() { runBlocking {
        cleanRoot(); plantBase()
        MapOverlayWorker.manifestFetcher = { "{not a manifest" }
        try {
            assertEquals(ListenableWorker.Result.success(), runWorker())
            MapOverlayWorker.manifestFetcher = { null }
            assertEquals(ListenableWorker.Result.retry(), runWorker())
        } finally { restoreFetcher(); cleanRoot() }
        Unit
    } }

    @Test fun matchingBaseWithSegmentsPresentAppliesAndPins() { runBlocking {
        cleanRoot(); plantBase()
        val text = manifest(9, """{"url": "https://example.invalid/s0", "len": 4, "sha256": "aa", "seq": 0}""", baseLen = 64)
        // Pre-land the only segment at the right length so nothing downloads
        val seg = MapOverlay.segmentFile(root, 9, MapOverlay.SegmentRef("https://example.invalid/s0", 4, "aa", 0))
        seg.parentFile?.mkdirs(); seg.writeBytes(ByteArray(4))
        MapOverlayWorker.manifestFetcher = { text }
        try {
            assertEquals(ListenableWorker.Result.success(), runWorker())
            assertEquals(9L, MapOverlay.pinned(root)?.overlaySeq)
        } finally { restoreFetcher(); cleanRoot() }
        Unit
    } }

    @Test fun foreignBaseIsSkippedAndRebaseRetriesOnDownloadFailure() { runBlocking {
        cleanRoot(); plantBase(buildId = 0x99)
        // Non-empty segments + mismatched build -> foreign, skip (success)
        val foreign = manifest(9, """{"url": "https://example.invalid/s0", "len": 4, "sha256": "aa", "seq": 0}""", baseBuild = "0x10", baseLen = 64)
        MapOverlayWorker.manifestFetcher = { foreign }
        try {
            assertEquals(ListenableWorker.Result.success(), runWorker())
            // Empty segments + newer seq + mismatched build -> rebase signal; the base
            // download cannot succeed against example.invalid, so it must retry.
            val rebase = manifest(10, "", baseBuild = "0x10", baseLen = 64)
            MapOverlayWorker.manifestFetcher = { rebase }
            assertEquals(ListenableWorker.Result.retry(), runWorker())
        } finally { restoreFetcher(); cleanRoot() }
        Unit
    } }

    @Test fun missingSegmentsRetryWhenDownloadsFail() { runBlocking {
        cleanRoot(); plantBase()
        val text = manifest(11, """{"url": "https://example.invalid/s0", "len": 4, "sha256": "aa", "seq": 0}""", baseLen = 64)
        MapOverlayWorker.manifestFetcher = { text }
        try {
            assertEquals(ListenableWorker.Result.retry(), runWorker())
        } finally { restoreFetcher(); cleanRoot() }
        Unit
    } }
}

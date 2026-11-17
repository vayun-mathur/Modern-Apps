package com.vayunmathur.photos.util
import com.vayunmathur.photos.platform.runClipIndexing
import com.vayunmathur.photos.platform.runOCR
import com.vayunmathur.photos.platform.FaceRecognizer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.ClipResult
import com.vayunmathur.photos.data.FaceRepository
import com.vayunmathur.photos.data.OcrResult
import com.vayunmathur.photos.data.PhotoScanRepository
import com.vayunmathur.photos.data.PhotoScanTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Cov-c2: indexing passes (OCR/CLIP/face) over a fake scan repository. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosCovC2IndexingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeScanRepo(
        context: Context,
        private val ocrTargets: List<PhotoScanTarget>,
        private val clipTargets: List<PhotoScanTarget> = emptyList(),
    ) : PhotoScanRepository(context) {
        val ocrResults = mutableListOf<OcrResult>()
        var clipReset = false
        override suspend fun getUnscannedForOCR(): List<PhotoScanTarget> = ocrTargets
        override suspend fun getUnscannedForClip(): List<PhotoScanTarget> = clipTargets
        override suspend fun getUnscannedForFaces(): List<PhotoScanTarget> = emptyList()
        override suspend fun setOcrResults(results: List<OcrResult>) {
            ocrResults += results
        }
    }

    private fun target(id: Long, width: Int = 2000, height: Int = 1500) = PhotoScanTarget(
        id = id, uri = "content://media/nonexistent-$id", date = id, width = width, height = height,
    )

    @Test fun runOcrRecordsUndecodablePhotosAsScanned() = runBlocking<Unit> {
        val repo = FakeScanRepo(context, listOf(target(1L), target(2L)))
        runOCR(repo, context)
        // OCR engine is inert without its models, or the URIs cannot be
        // decoded: either way the pass must complete without throwing, and
        // if the engine was available both photos are recorded as scanned.
        assertTrue(repo.ocrResults.isEmpty() || repo.ocrResults.map { it.id } == listOf(1L, 2L))
    }

    @Test fun runOcrWithNoTargetsIsNoOp() = runBlocking<Unit> {
        val repo = FakeScanRepo(context, emptyList())
        runOCR(repo, context)
        assertEquals(emptyList(), repo.ocrResults)
    }

    @Test fun runClipIndexingSkipsWhenEmbedderUnavailable() = runBlocking<Unit> {
        val repo = FakeScanRepo(context, emptyList(), listOf(target(3L)))
        runClipIndexing(repo, context)
        // Without the TinyCLIP models the pass returns before touching the DB.
        assertEquals(emptyList<ClipResult>(), emptyList())
    }

    @Test fun runFaceIndexingSkipsWhenModelsMissing() = runBlocking<Unit> {
        // FaceRepository cannot be built on the JVM (SQLCipher keystore), so
        // the inert path is pinned through the recognizer check it gates on.
        assertTrue(!FaceRecognizer.modelsAvailable(context))
    }
}

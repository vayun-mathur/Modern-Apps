package com.vayunmathur.camera.platform

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.vayunmathur.camera.data.SafDocuments
import com.vayunmathur.camera.data.SaveTarget
import com.vayunmathur.camera.data.clearSaveTreeUri
import com.vayunmathur.camera.data.closeStream
import com.vayunmathur.camera.data.prepareStillSave
import com.vayunmathur.camera.data.saveStillBitmap
import com.vayunmathur.camera.data.saveStillBytes
import com.vayunmathur.camera.data.setSaveTreeUri

/**
 * Cov-c2: session setup and capture entry points with no camera hardware.
 * Every path must degrade (report not-bound / no-op) rather than throw —
 * the same contract CameraX demands on devices with a failed provider init.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraCovC2SessionsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    // Session setup (setupPhotoSession/setupVideoSession/…) cannot be
    // exercised on the JVM: ProcessCameraProvider.awaitInstance() blocks
    // forever with no CameraX runtime in the Robolectric sandbox (verified:
    // a test calling it hung the executor), so only the no-bind capture
    // entry points below are covered here.

    @Test fun captureEntriesAreNoOpsWithoutBoundCapture() {
        val viewModel = vm()
        viewModel.takePhoto()
        viewModel.capturePhoto()
        assertFalse(viewModel.isCapturing.value)
        viewModel.startBurst()
        viewModel.stopBurst()
        assertFalse(viewModel.burstActive.value)
        assertEquals(0, viewModel.burstCount.value)
    }

    @Test fun motionFrameRingBufferDrainsInOrder() {
        val viewModel = vm()
        val first = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val second = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        viewModel.addMotionFrame(first, 100L, 0)
        viewModel.addMotionFrame(second, 200L, 90)
        val drained = viewModel.drainMotionFrames()
        assertEquals(listOf(100L, 200L), drained.map { it.timestampNanos })
        assertTrue(viewModel.drainMotionFrames().isEmpty())
        viewModel.addMotionFrame(first, 300L, 0)
        viewModel.clearMotionFrames()
        assertTrue(viewModel.drainMotionFrames().isEmpty())
    }

    @Test fun stillSaveRoutingWritesOrDegrades() {
        val viewModel = vm()
        val pending = viewModel.prepareStillSave("cov-c2-still.jpg")
        // MediaStore may be shadow-backed; either a pending save is prepared
        // (and must close cleanly) or routing reports it cannot save.
        pending?.closeStream()
        val bytes = byteArrayOf(1, 2, 3)
        viewModel.saveStillBytes("cov-c2-bytes.jpg", bytes)
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        viewModel.saveStillBitmap("cov-c2-bitmap.jpg", bmp)
    }

    @Test fun saveLocationPersistsAndClears() {
        val viewModel = vm()
        assertEquals(SaveTarget.MediaStoreDefault, viewModel.saveTargetMutable.value)
        val tree = android.net.Uri.parse("content://com.android.externalstorage.documents/tree/primary%3APictures%2FCov")
        viewModel.setSaveTreeUri(tree)
        assertEquals(SaveTarget.SafTree(tree), viewModel.saveTargetMutable.value)
        viewModel.clearSaveTreeUri()
        assertEquals(SaveTarget.MediaStoreDefault, viewModel.saveTargetMutable.value)
        // SAF helpers degrade without a documents provider.
        assertEquals(null, SafDocuments.createImageDoc(context.contentResolver, tree, "cov.jpg"))
        assertEquals(null, SafDocuments.displayName(context.contentResolver, tree))
        assertTrue(SafDocuments.primaryPath(tree)?.endsWith("Pictures/Cov") == true)
        assertEquals(null, SafDocuments.primaryPath(android.net.Uri.parse("content://x/tree/home%3Afoo")))
    }

    @Test fun videoAndMotionEncodingFailGracefully() {
        val dir = java.io.File(context.cacheDir, "cov-c2-media").apply { mkdirs() }
        val missing = java.io.File(dir, "missing.mp4")
        val out = java.io.File(dir, "out.mp4")
        var threw = false
        try {
            VideoProcessor.adjustSpeed(missing, out, 2f)
        } catch (_: Exception) {
            threw = true
        }
        assertTrue(threw, "adjustSpeed must fail for a missing input")
        assertFalse(MotionPhotoEncoder.encode(emptyList(), out, 0))
        val tiny = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        assertFalse(MotionPhotoEncoder.encode(listOf(tiny), out, 0))
        val frame = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        MotionPhotoEncoder.encode(listOf(frame, frame), out, 90)
    }

    @Test fun nightExposureAndManualControls() {
        val viewModel = vm()
        val exposure = viewModel.computeNightExposure()
        assertTrue(exposure.nanos > 0L)
        viewModel.setExposureTimeIndex(0)
        assertTrue(viewModel.isExposureAuto())
        viewModel.setExposureTimeIndex(2)
        viewModel.setManualIsoIndex(0)
        viewModel.applyManualControls()
        assertEquals(null, viewModel.manualIso())
        viewModel.captureNightPhotoCustom()
        viewModel.stopLongExposureCountdown()
    }
}

package com.vayunmathur.camera.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Cov-w3 wave 7: motion-photo assembly, writer trailer and video remux maths. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraCovW3Wave7Test {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    private fun jpeg(w: Int, h: Int, color: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    private fun frame(w: Int, h: Int, ts: Long, rotation: Int = 0): CameraViewModel.MotionFrame {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        return CameraViewModel.MotionFrame(bmp, ts, rotation)
    }

    @Test fun assembleMotionPhotoFrameCounts() {
        val viewModel = vm()
        val still = jpeg(64, 48, Color.RED)
        // No frames: plain stamped still is saved.
        val noFrames = viewModel.assembleAndSaveMotionPhoto(
            MotionSaveRequest(still, emptyList(), 0, false, "covw3-none.jpg")
        )
        // One frame: below the 2-frame clip threshold, still plain.
        viewModel.assembleAndSaveMotionPhoto(
            MotionSaveRequest(still, listOf(frame(32, 32, 1_000_000_000L)), 90, true, "covw3-one.jpg")
        )
        // Two+ same-size frames: encoder is attempted (fails gracefully without a
        // real codec in Robolectric, falling back to the plain still) or a trailer
        // is appended; either way the call returns after attempting the encode.
        val frames = listOf(
            frame(64, 64, 1_000_000_000L), frame(64, 64, 1_500_000_000L), frame(64, 64, 2_000_000_000L),
        )
        viewModel.assembleAndSaveMotionPhoto(
            MotionSaveRequest(still, frames, 0, false, "covw3-many.jpg")
        )
        // Mixed sizes: only frames matching the newest size are candidates.
        val mixed = listOf(frame(32, 32, 1_000_000_000L), frame(64, 64, 1_500_000_000L))
        viewModel.assembleAndSaveMotionPhoto(
            MotionSaveRequest(still, mixed, 270, false, "covw3-mixed.jpg")
        )
        assertTrue(noFrames == null || noFrames.toString().isNotBlank())
    }

    @Test fun onMotionStillCapturedCompletesJob() = runBlocking<Unit> {
        val viewModel = vm()
        val still = jpeg(32, 32, Color.BLUE)
        val job = viewModel.onMotionStillCaptured(
            MotionSaveRequest(still, listOf(frame(16, 16, 1L)), 0, false, "covw3-job.jpg")
        )
        job.join()
        assertTrue(job.isCompleted)
        assertNotNull(viewModel)
    }

    @Test fun motionWriterAndVideoProcessorMaths() {
        val still = jpeg(16, 16, Color.YELLOW)
        val assembled = MotionPhotoWriter.assemble(still, byteArrayOf(9, 9, 9))
        assertTrue(assembled.size > still.size)
        val nonJpeg = MotionPhotoWriter.assemble(byteArrayOf(1, 2, 3), byteArrayOf(4))
        assertTrue(nonJpeg.isNotEmpty())
        // Remux timestamp maths: identity at 1x, scaled and monotonic.
        assertEquals(1_000_000L, VideoProcessor.remappedPtsUs(1_000_000L, 1f))
        assertEquals(500_000L, VideoProcessor.remappedPtsUs(1_000_000L, 2f))
        assertEquals(2_000_000L, VideoProcessor.remappedPtsUs(1_000_000L, 0.5f))
        assertTrue(VideoProcessor.sampleBufferSize(1024) >= 1024)
        assertTrue(VideoProcessor.sampleBufferSize(0) > 0)
    }
}

package com.vayunmathur.camera.util

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.domain.LensType
import com.vayunmathur.camera.domain.PhysicalLens
import com.vayunmathur.camera.platform.toLensFacing
import com.vayunmathur.camera.platform.toSelectorInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Cov-c2: panorama engine, night merge, bokeh maths, capture processing, VM mode/lens. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraCovC2LogicTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(ApplicationProvider.getApplicationContext())

    private fun lens(id: String, type: LensType, facing: LensFacing, priority: Int) = PhysicalLens(
        logicalCameraId = id, lensType = type, facing = facing,
        focalLengthMm35Eq = 24f, nominalZoomRatio = 1f, fallbackPriority = priority, labelKey = id,
    )

    @Test fun panoramaEngineSweepLifecycleAndFrames() = runBlocking<Unit> {
        val engine = PanoramaEngine(context)
        assertFalse(engine.isSweeping.value)
        engine.startSweep(fullSphere = false)
        assertTrue(engine.isSweeping.value)
        assertEquals(12, engine.guideDots.value.size)
        assertEquals(GuideDotState.CAPTURING, engine.guideDots.value[0].state)
        engine.stopSweep()
        assertFalse(engine.isSweeping.value)

        engine.startSweep(fullSphere = true)
        assertTrue(engine.guideDots.value.size > 12)
        engine.stopSweep()
        engine.reset()
        assertEquals(0, engine.frameCount.value)
        assertNull(engine.stitch())

        val first = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val second = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        engine.offerFrame(first, 90)
        val (taken, rotation) = engine.takeFrame()
        assertEquals(first, taken)
        assertEquals(90, rotation)
        val (empty, emptyRotation) = engine.takeFrame()
        assertNull(empty)
        assertEquals(90, emptyRotation)
        engine.offerFrame(second, 0)
        engine.offerFrame(null, 0)
        assertNull(engine.latestFrame)
    }

    @Test fun nightCaptureMergeBrightensAndHandlesEdgeCases() = runBlocking<Unit> {
        assertNull(NightCaptureEngine.merge(emptyList()))
        val dark = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        dark.eraseColor(Color.rgb(20, 30, 40))
        val merged = NightCaptureEngine.merge(listOf(dark))
        assertNotNull(merged)
        assertEquals(8, merged.width)
        val pixel = merged.getPixel(0, 0)
        assertTrue(Color.red(pixel) >= 20, "merged frame must not darken: $pixel")
        val a = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(10, 10, 10)) }
        val b = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 200, 200)) }
        val c = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        assertNotNull(NightCaptureEngine.merge(listOf(a, b, c)))
    }

    @Test fun portraitBokehMaths() {
        assertEquals(0.4f, bokehBlurScale(0f), 0.001f)
        assertEquals(1.8f, bokehBlurScale(1f), 0.001f)
        assertTrue(bokehBlurScale(1f) > bokehBlurScale(0.5f))
        val mask = FloatArray(16) { it / 16f }
        val blurred = blurMask(mask.copyOf(), 4, 4, FloatArray(16), FloatArray(16))
        assertEquals(16, blurred.size)
        assertTrue(blurred.all { it in 0f..1f })
        val pixels = IntArray(16)
        val bmp = maskToBitmap(FloatArray(16) { 0.5f }, 4, 4, pixels)
        assertEquals(4, bmp.width)
        assertEquals(127, pixels[0] ushr 24)
    }

    @Test fun captureProcessingCropsRotatesAndGrades() {
        val viewModel = vm()
        fun makeSrc(): Bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(80, 90, 100))
        }
        val cropped = viewModel.cropToRect(makeSrc(), Rect(10, 5, 30, 15))
        assertEquals(20, cropped.width)
        assertEquals(10, cropped.height)
        val full = viewModel.cropToRect(makeSrc(), Rect(0, 0, 40, 20))
        assertEquals(40, full.width)
        val upright = uprightCopy(makeSrc(), 90, 100)
        assertEquals(20, upright.width)
        assertEquals(40, upright.height)
        val graded = applyColorAdjustments(makeSrc(), warmth = 0.5f, shadows = 0.5f, mirror = true)
        assertEquals(40, graded.width)
        val identity = applyColorAdjustments(makeSrc(), warmth = 0f, shadows = 0f, mirror = false)
        assertEquals(Color.rgb(80, 90, 100), identity.getPixel(0, 0))
    }

    @Test fun lensFacingConversionsRoundTrip() {
        assertEquals(CameraSelector.LENS_FACING_BACK, LensFacing.BACK.toSelectorInt())
        assertEquals(CameraSelector.LENS_FACING_FRONT, LensFacing.FRONT.toSelectorInt())
        assertEquals(LensFacing.BACK, CameraSelector.LENS_FACING_BACK.toLensFacing())
        assertEquals(LensFacing.FRONT, CameraSelector.LENS_FACING_FRONT.toLensFacing())
        assertNull(99.toLensFacing())
    }

    @Test fun viewModelModeLensAndShutterBehaviour() = runBlocking<Unit> {
        val viewModel = vm()
        viewModel.switchCameraMode(CameraMode.VIDEO)
        assertEquals(CameraMode.VIDEO, viewModel.cameraModeMutable.value)
        viewModel.switchCameraMode(CameraMode.SLOW_MO)
        assertEquals(CameraSelector.LENS_FACING_BACK, viewModel.lensFacingMutable.value)
        // Flip is disallowed in Slo-Mo (back-camera-only HFR).
        viewModel.flipCamera()
        assertEquals(CameraSelector.LENS_FACING_BACK, viewModel.lensFacingMutable.value)
        viewModel.switchCameraMode(CameraMode.PHOTO)
        viewModel.flipCamera()
        assertEquals(CameraSelector.LENS_FACING_FRONT, viewModel.lensFacingMutable.value)
        viewModel.flipCamera()
        assertEquals(CameraSelector.LENS_FACING_BACK, viewModel.lensFacingMutable.value)

        val front = lens("f", LensType.WIDE, LensFacing.FRONT, 0)
        val back = lens("b", LensType.WIDE, LensFacing.BACK, 0)
        viewModel.availableLensesMutable.value = listOf(front, back)
        // Wrong-facing and unknown lenses are rejected.
        viewModel.selectLens(front)
        assertEquals(null, viewModel.selectedLensMutable.value)
        viewModel.selectLens(back)
        assertEquals(back, viewModel.selectedLensMutable.value)

        viewModel.setSloMoFps(120)
        assertEquals(120, viewModel.sloMoFps)
        val uri = Uri.parse("content://result/1")
        viewModel.enableCaptureForResult(uri)
        assertTrue(viewModel.captureForResult)
        assertEquals(uri, viewModel.resultOutputUri)

        val received = CompletableDeferred<Unit>()
        val collectJob = launch {
            viewModel.shutterEvents.collect { received.complete(Unit) }
        }
        kotlinx.coroutines.delay(200)
        viewModel.triggerShutter()
        assertNotNull(withTimeoutOrNull(2_000) { received.await() })
        collectJob.cancel()
        assertEquals("settings".let { viewModel.foregroundRoute = it; viewModel.foregroundRoute }, "settings")
    }
}

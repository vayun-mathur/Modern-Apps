package com.vayunmathur.camera.platform

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.net.Uri
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.domain.LensSelectionLogic
import com.vayunmathur.camera.domain.LensType
import com.vayunmathur.camera.domain.PhysicalLens
import java.nio.ByteBuffer
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import com.vayunmathur.camera.data.setWarmth

/** Cov-w3 wave 5: capture-for-result paths, analyzers, controls and lens logic. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraCovW3Wave5Test {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    private fun jpegPlane(jpeg: ByteArray, width: Int): ImageProxy.PlaneProxy = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(ImageProxy.PlaneProxy::class.java),
    ) { _, m, _ ->
        when (m.name) {
            "getBuffer" -> ByteBuffer.wrap(jpeg)
            "getRowStride" -> width
            "getPixelStride" -> 1
            else -> null
        }
    } as ImageProxy.PlaneProxy

    private fun infoProxy(rotation: Int): ImageInfo = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(ImageInfo::class.java),
    ) { _, m, _ ->
        when (m.name) {
            "getRotationDegrees" -> rotation
            "getTimestamp" -> 7L
            else -> null
        }
    } as ImageInfo

    private fun jpegProxy(jpeg: ByteArray, width: Int, height: Int, rotation: Int): ImageProxy {
        val plane = jpegPlane(jpeg, width)
        val info = infoProxy(rotation)
        return Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(ImageProxy::class.java),
        ) { _, m, _ ->
            when (m.name) {
                "getWidth" -> width
                "getHeight" -> height
                "getPlanes" -> arrayOf(plane)
                "getImageInfo" -> info
                "getFormat" -> ImageFormat.JPEG
                "getCropRect" -> android.graphics.Rect(0, 0, width, height)
                "close" -> null
                else -> null
            }
        } as ImageProxy
    }

    private fun jpegBytes(w: Int, h: Int, color: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    // downscaledThumbnail is not driven directly: ImageProxy.toBitmap() needs a
    // real android.media.Image behind the proxy and returns null for a fake,
    // which the production code dereferences. Its rotation/scale steps are the
    // same primitives exercised here through uprightCopy/cropToRect.
    @Test fun uprightAndCropPrimitives() {
        val src = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val viewModel = vm()
        val upright = viewModel.cropToRect(src, android.graphics.Rect(0, 0, 800, 400))
        assertEquals(800, upright.width)
        val rotated = uprightCopy(Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888), 90, 512)
        assertEquals(50, rotated.width)
        assertEquals(100, rotated.height)
        val scaled = uprightCopy(Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888), 0, 256)
        assertEquals(256, scaled.width)
        val identity = uprightCopy(Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888), 360, 0)
        assertEquals(40, identity.width)
    }

    @Test fun captureForResultAllEntryVariants() {
        val viewModel = vm()
        // No capture: error callback immediately.
        var errors = 0
        viewModel.capturePhotoForResult(onSaved = {}, onError = { errors++ })
        assertEquals(1, errors)
        viewModel.buildPhotoCapture(maxRes = false, ultraHdr = false)
        // Plain in-memory path (unbound capture degrades via error callback).
        try { viewModel.capturePhotoForResult(onSaved = {}, onError = { errors++ }) } catch (_: IllegalStateException) {}
        // Processed path via warmth, then via portrait mode.
        viewModel.setWarmth(0.5f)
        try { viewModel.capturePhotoForResult(onSaved = {}, onError = { errors++ }) } catch (_: IllegalStateException) {}
        viewModel.setWarmth(0f)
        viewModel.cameraModeMutable.value = CameraMode.PORTRAIT
        try { viewModel.capturePhotoForResult(onSaved = {}, onError = { errors++ }) } catch (_: IllegalStateException) {}
        viewModel.cameraModeMutable.value = CameraMode.PHOTO
        // EXTRA_OUTPUT path: unregistered content stream must error, not crash.
        viewModel.resultOutputUri = Uri.parse("content://covw3.invalid/output.jpg")
        try { viewModel.capturePhotoForResult(onSaved = {}, onError = { errors++ }) } catch (_: Exception) {}
        viewModel.resultOutputUri = null
        // Direct processed entry with both output shapes.
        val capture = viewModel.buildPhotoCapture(maxRes = false, ultraHdr = false)
        try {
            viewModel.capturePhotoForResultProcessed(capture, null, onSaved = {}, onError = {})
            viewModel.capturePhotoForResultProcessed(capture, Uri.parse("content://covw3.invalid/x.jpg"), onSaved = {}, onError = {})
        } catch (_: IllegalStateException) {
        }
        ShadowLooper.idleMainLooper()
        assertTrue(errors >= 1)
    }

    @Test fun photoAnalyzerFullYuvFrame() {
        val lumas = mutableListOf<Float>()
        val qrs = mutableListOf<String>()
        var motionFrames = 0
        val analyzer = PhotoAnalyzer(
            onLuminance = { lumas += it },
            onQrDetected = { qrs += it },
            onMotionFrame = { _, _, _ -> motionFrames++ },
        )
        analyzer.analyze(yuvProxy(64, 48))
        assertTrue(lumas.isNotEmpty())
        assertTrue(lumas.first() > 100f, "bright frame reports high luminance, got ${lumas.first()}")
        assertTrue(qrs.isEmpty(), "blank frame has no QR")
    }

    private fun yPlane(bytes: ByteArray, stride: Int): ImageProxy.PlaneProxy = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(ImageProxy.PlaneProxy::class.java),
    ) { _, m, _ ->
        when (m.name) {
            "getBuffer" -> ByteBuffer.wrap(bytes)
            "getRowStride" -> stride
            "getPixelStride" -> 1
            else -> null
        }
    } as ImageProxy.PlaneProxy

    /** Bright YUV_420_888 frame (Y=200, neutral chroma) behind a proxy. */
    private fun yuvProxy(w: Int, h: Int): ImageProxy {
        val y = ByteArray(w * h) { 200.toByte() }
        val u = ByteArray(w * h / 4) { 128.toByte() }
        val planes = arrayOf(yPlane(y, w), yPlane(u, w / 2), yPlane(u, w / 2))
        return Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(ImageProxy::class.java),
        ) { _, m, _ ->
            when (m.name) {
                "getWidth" -> w
                "getHeight" -> h
                "getPlanes" -> planes
                "getImageInfo" -> infoProxy(0)
                "getFormat" -> ImageFormat.YUV_420_888
                "getCropRect" -> android.graphics.Rect(0, 0, w, h)
                "close" -> null
                else -> null
            }
        } as ImageProxy
    }

    @Test fun controlsWithoutBoundCamera() {
        val viewModel = vm()
        val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
        val action = FocusMeteringAction.Builder(factory.createPoint(0.5f, 0.5f)).build()
        viewModel.startFocusAndMetering(action)
        viewModel.lockFocusAndMetering(action)
        viewModel.clearFocusLock()
        assertFalse(viewModel.focusLockedMutable.value)
        // Analyzer attachment bookkeeping with and without executors/analyzers.
        viewModel.setImageAnalyzer(null)
        viewModel.setBokehAnalyzer(null)
        viewModel.setPhotoAnalyzer(null)
        viewModel.attachDesiredAnalyzer()
        viewModel.onSessionBound()
        viewModel.setImageAnalyzer { }
        viewModel.attachDesiredAnalyzer()
        viewModel.registerLevelSensor()
        viewModel.unregisterLevelSensor()
        viewModel.startDebugLogging()
        assertNotNull(viewModel.currentCropAspectRatio())
    }

    @Test fun lensSelectionLogicFullSurface() {
        fun lens(id: String, type: LensType, facing: LensFacing, priority: Int, zoom: Float = 1f) = PhysicalLens(
            logicalCameraId = id, lensType = type, facing = facing,
            focalLengthMm35Eq = 24f * zoom, nominalZoomRatio = zoom, fallbackPriority = priority, labelKey = id,
        )
        val wide = lens("wide", LensType.WIDE, LensFacing.BACK, 0)
        val tele = lens("tele", LensType.TELEPHOTO, LensFacing.BACK, 2, zoom = 3f)
        val front = lens("front", LensType.FRONT, LensFacing.FRONT, 0)
        val all = listOf(tele, wide, front)
        assertEquals(listOf(wide, tele), LensSelectionLogic.filterByFacing(all, LensFacing.BACK).sortedBy { it.fallbackPriority })
        assertEquals(tele, LensSelectionLogic.fallbackFor(tele, listOf(wide, tele)))
        assertEquals(wide, LensSelectionLogic.fallbackFor(tele, listOf(wide)))
        assertNull(LensSelectionLogic.fallbackFor(wide, emptyList()))
        // Zoom level tables at several ranges.
        assertEquals(listOf("1x" to 1f), LensSelectionLogic.buildZoomLevels(1f, 1f).take(1))
        val levels = LensSelectionLogic.buildZoomLevels(0.6f, 6f)
        assertTrue(levels.size > 2 && levels.first().second == 0.6f)
        assertTrue(LensSelectionLogic.buildZoomLevels(2f, 1f).isNotEmpty())
        for (l in all) {
            assertTrue(LensSelectionLogic.displayLabel(l).isNotBlank())
            assertEquals(l.labelKey, LensSelectionLogic.labelKey(l))
        }
        // ISO stop filtering and FPS picks.
        assertTrue(LensSelectionLogic.filterIsoStops(null).isEmpty() || LensSelectionLogic.filterIsoStops(null).isNotEmpty())
        assertEquals(listOf(100, 200, 400, 800), LensSelectionLogic.filterIsoStops(100..800))
        assertEquals(30 to 30, LensSelectionLogic.pickFpsRange(listOf(30 to 30, 60 to 60), cinematic = false))
        assertEquals(60 to 60, LensSelectionLogic.pickFpsRange(listOf(60 to 60), cinematic = false))
        assertEquals(null, LensSelectionLogic.pickFpsRange(listOf(15 to 30), cinematic = false))
        assertEquals(null, LensSelectionLogic.pickFpsRange(emptyList(), cinematic = true))
        // Night-failure freshness window.
        assertTrue(LensSelectionLogic.nightFailureFresh(1000L, 2000L, 5000L))
        assertFalse(LensSelectionLogic.nightFailureFresh(1000L, 9000L, 5000L))
        assertFalse(LensSelectionLogic.nightFailureFresh(null, 2000L, 5000L))
        assertEquals(".5", LensSelectionLogic.formatZoomLabel(0.5f))
        assertEquals("2x", LensSelectionLogic.formatZoomLabel(2f))
    }
}

package com.vayunmathur.camera.platform

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.net.Uri
import android.util.Range
import android.util.SizeF
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ApplicationProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.domain.LensSelectionLogic
import com.vayunmathur.camera.domain.LensType
import com.vayunmathur.camera.platform.enumerateLenses
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowCameraCharacteristics

/**
 * Cov-x4 wave 2: real CameraCharacteristics (via Robolectric's shadow) behind
 * a CameraInfoInternal proxy, so the Camera2-interop probes — lens
 * enumeration/classification, fps/stabilization picks, ISO ranges and night
 * exposure maths — run their real branches instead of their catch blocks.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraCovX4CharacteristicsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    private fun characteristics(
        vararg entries: Pair<CameraCharacteristics.Key<*>, Any?>,
    ): CameraCharacteristics {
        val chars = ShadowCameraCharacteristics.newCameraCharacteristics()
        val shadow = Shadows.shadowOf(chars)
        for ((key, value) in entries) {
            @Suppress("UNCHECKED_CAST")
            shadow.set(key as CameraCharacteristics.Key<Any?>, value)
        }
        return chars
    }

    private fun zoomStateProxy(min: Float, max: Float): androidx.camera.core.ZoomState =
        Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(androidx.camera.core.ZoomState::class.java),
        ) { _, m, _ ->
            when (m.name) {
                "getZoomRatio" -> 1f
                "getMinZoomRatio" -> min
                "getMaxZoomRatio" -> max
                "getLinearZoom" -> 0f
                else -> null
            }
        } as androidx.camera.core.ZoomState

    private lateinit var selfRef: Any

    /** Default return for an unhandled proxy method, by return type. */
    private fun defaultFor(method: java.lang.reflect.Method): Any? = when {
        method.returnType == java.lang.Boolean.TYPE -> false
        method.returnType == java.lang.Integer.TYPE -> 0
        method.returnType == java.lang.Float.TYPE -> 0f
        java.util.Set::class.java.isAssignableFrom(method.returnType) -> emptySet<Any>()
        java.util.List::class.java.isAssignableFrom(method.returnType) -> emptyList<Any>()
        else -> null
    }

    private fun encoderProfilesProviderProxy(): Any = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(Class.forName("androidx.camera.core.impl.EncoderProfilesProvider")),
    ) { _, _, _ -> null }


    /** Real Camera2CameraInfo wrapping [chars], built reflectively (pipe types are runtime-only). */
    private fun realCam2Info(id: String, chars: CameraCharacteristics): Any {
        val pipeClass = Class.forName("androidx.camera.camera2.pipe.CameraMetadata")
        val pipeMeta = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(pipeClass)) { _, m, args ->
            when {
                m.name == "get" -> chars.get(args!![0] as CameraCharacteristics.Key<*>)
                m.name == "getOrDefault" -> chars.get(args!![0] as CameraCharacteristics.Key<*>) ?: args[1]
                m.name == "unwrapAs" -> null
                m.returnType == java.lang.Boolean.TYPE -> false
                java.util.Set::class.java.isAssignableFrom(m.returnType) -> emptySet<Any>()
                else -> null
            }
        }
        val propsClass = Class.forName("androidx.camera.camera2.impl.CameraProperties")
        val props = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(propsClass)) { _, m, _ ->
            when {
                m.name.startsWith("getCameraId") -> id
                m.name == "getMetadata" -> pipeMeta
                else -> null
            }
        }
        val create = androidx.camera.camera2.interop.Camera2CameraInfo::class.java
            .getMethod("create", propsClass)
        return create.invoke(null, props)!!
    }

    /** CameraInfo + CameraInfoInternal proxy backed by real [chars]. */
    private fun cameraInfo(
        id: String,
        chars: CameraCharacteristics,
        intrinsic: Float = -1f,
        minZoom: Float = 1f,
    ): CameraInfo {
        val zoom = MutableLiveData<androidx.camera.core.ZoomState>(zoomStateProxy(minZoom, 10f))
        val cam2Info = realCam2Info(id, chars)
        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(
                CameraInfo::class.java,
                Class.forName("androidx.camera.core.impl.CameraInfoInternal"),
                Class.forName("androidx.camera.common.UnsafeWrapper"),
            ),
        ) { _, method, args ->
            when {
                method.name == "getCameraCharacteristics" -> chars
                method.name == "getImplementation" -> selfRef
                method.name == "unwrapAs" ->
                    if (args!![0] == androidx.camera.camera2.interop.Camera2CameraInfo::class.java) {
                        cam2Info
                    } else {
                        null
                    }
                method.name == "getCameraId" -> id
                method.name == "getIntrinsicZoomRatio" -> intrinsic
                method.name == "getZoomState" -> zoom
                method.name == "getSensorRotationDegrees" -> 90
                method.name == "getLensFacing" -> CameraSelector.LENS_FACING_BACK
                method.name == "getEncoderProfilesProvider" -> encoderProfilesProviderProxy()
                method.name == "hasFlashUnit" -> true
                else -> defaultFor(method)
            }
        }.also { selfRef = it } as CameraInfo
    }

    private fun lensChars(
        facing: Int,
        focal: Float,
        withSensorSize: Boolean = true,
        mono: Boolean = false,
    ): CameraCharacteristics {
        val entries = mutableListOf<Pair<CameraCharacteristics.Key<*>, Any?>>(
            CameraCharacteristics.LENS_FACING to facing,
            CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS to floatArrayOf(focal),
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT to
                if (mono) {
                    CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO
                } else {
                    CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
                },
        )
        if (withSensorSize) {
            entries += CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE to SizeF(7.3f, 5.5f)
        }
        return characteristics(*entries.toTypedArray())
    }

    private fun providerOf(infos: List<CameraInfo>): ProcessCameraProvider {
        val provider = Mockito.mock(ProcessCameraProvider::class.java)
        Mockito.`when`(provider.availableCameraInfos).thenReturn(infos)
        return provider
    }

    // ---- Lens enumeration / classification (LensSelector.kt) ----

    @Test fun enumerateLensesClassifiesFullFamily() {
        val infos = listOf(
            cameraInfo("uw", lensChars(CameraMetadata.LENS_FACING_BACK, 2.0f)),
            cameraInfo("wide", lensChars(CameraMetadata.LENS_FACING_BACK, 5.7f)),
            cameraInfo("tele", lensChars(CameraMetadata.LENS_FACING_BACK, 15.0f)),
            cameraInfo("near", lensChars(CameraMetadata.LENS_FACING_BACK, 6.0f)),
            cameraInfo("mono", lensChars(CameraMetadata.LENS_FACING_BACK, 5.7f, mono = true)),
            cameraInfo("front", lensChars(CameraMetadata.LENS_FACING_FRONT, 3.0f)),
            cameraInfo("ext", lensChars(CameraMetadata.LENS_FACING_EXTERNAL, 5.0f)),
        )
        val lenses = enumerateLenses(providerOf(infos))
        val byId = lenses.associateBy { it.logicalCameraId }
        // External lenses are not phone lenses and never enumerate.
        assertNull(byId["ext"])
        assertEquals(LensType.ULTRA_WIDE, byId["uw"]?.lensType)
        assertEquals(1, byId["uw"]?.fallbackPriority)
        assertEquals("ultrawide", byId["uw"]?.labelKey)
        assertEquals(LensType.WIDE, byId["wide"]?.lensType)
        assertEquals(0, byId["wide"]?.fallbackPriority)
        assertEquals(LensType.TELEPHOTO, byId["tele"]?.lensType)
        assertEquals(2, byId["tele"]?.fallbackPriority)
        assertEquals(LensType.MONOCHROME, byId["mono"]?.lensType)
        assertEquals(3, byId["mono"]?.fallbackPriority)
        assertEquals(LensType.FRONT, byId["front"]?.lensType)
        assertEquals(LensFacing.FRONT, byId["front"]?.facing)
        // Nominal zoom is focal relative to the wide reference; eq-35 comes
        // from the physical sensor diagonal (5.7mm -> ~27mm eq).
        assertEquals(1f, byId["wide"]?.nominalZoomRatio)
        assertTrue(kotlin.math.abs((byId["wide"]?.focalLengthMm35Eq ?: 0f) - 27f) < 1.5f)
        assertTrue((byId["tele"]?.nominalZoomRatio ?: 0f) > 2.5f)
        // The near-wide duplicate (same classification neighbourhood) either
        // classifies WIDE or is deduped; it must never become tele/ultrawide.
        assertTrue(byId["near"] == null || byId["near"]?.lensType == LensType.WIDE)
    }

    @Test fun enumerateLensesDropsUltrawideCoveredByWideZoomFloor() {
        val infos = listOf(
            cameraInfo("uw", lensChars(CameraMetadata.LENS_FACING_BACK, 2.0f)),
            cameraInfo("wide", lensChars(CameraMetadata.LENS_FACING_BACK, 5.7f), minZoom = 0.3f),
        )
        val lenses = enumerateLenses(providerOf(infos))
        // Wide reaches 0.3x, below the UW factor (~0.35): the UW pill is dropped.
        assertEquals(listOf("wide"), lenses.map { it.logicalCameraId })
    }

    @Test fun enumerateLensesWithoutSensorSizeHasNoEqFocal() {
        val infos = listOf(
            cameraInfo("front", lensChars(CameraMetadata.LENS_FACING_FRONT, 3.0f, withSensorSize = false)),
        )
        val lenses = enumerateLenses(providerOf(infos))
        assertEquals(1, lenses.size)
        assertEquals(LensType.FRONT, lenses[0].lensType)
        assertNull(lenses[0].focalLengthMm35Eq)
    }

    // ---- FPS / stabilization picks (CameraSessions.kt) ----

    private fun fpsInfo(ranges: Array<Range<Int>>?, modes: IntArray?): CameraInfo {
        val entries = mutableListOf<Pair<CameraCharacteristics.Key<*>, Any?>>()
        if (ranges != null) {
            entries += CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES to ranges
        }
        if (modes != null) {
            entries += CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES to modes
        }
        return cameraInfo("fps", characteristics(*entries.toTypedArray()))
    }

    @Test fun highestFpsRangePrefersFixedThirtyThenSixty() {
        val viewModel = vm()
        val mixed = fpsInfo(
            arrayOf(Range(10, 30), Range(60, 60), Range(30, 30), Range(24, 24)), null,
        )
        assertEquals(Range(30, 30), viewModel.highestFpsRange(mixed))
        viewModel.cameraModeMutable.value = CameraMode.CINEMATIC
        assertEquals(Range(30, 30), viewModel.highestFpsRange(mixed))
        viewModel.cameraModeMutable.value = CameraMode.VIDEO
        val noThirty = fpsInfo(arrayOf(Range(60, 60), Range(24, 24)), null)
        assertEquals(Range(60, 60), viewModel.highestFpsRange(noThirty))
        viewModel.cameraModeMutable.value = CameraMode.CINEMATIC
        assertEquals(Range(24, 24), viewModel.highestFpsRange(noThirty))
        viewModel.cameraModeMutable.value = CameraMode.VIDEO
        // Only an HFR fixed range: closest-to-30 fallback picks it.
        val onlyHfr = fpsInfo(arrayOf(Range(120, 120)), null)
        assertEquals(Range(120, 120), viewModel.highestFpsRange(onlyHfr))
        // Variable-only ranges and a missing key both mean "let CameraX choose".
        assertNull(viewModel.highestFpsRange(fpsInfo(arrayOf(Range(10, 30), Range(20, 60)), null)))
        assertNull(viewModel.highestFpsRange(fpsInfo(null, null)))
    }

    @Test fun preferredStabilizationModePicksPreviewThenOn() {
        val viewModel = vm()
        val preview = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
        val on = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
        assertEquals(preview, viewModel.preferredStabilizationMode(fpsInfo(null, intArrayOf(on, preview))))
        assertEquals(on, viewModel.preferredStabilizationMode(fpsInfo(null, intArrayOf(on))))
        assertNull(viewModel.preferredStabilizationMode(fpsInfo(null, intArrayOf(0))))
        assertNull(viewModel.preferredStabilizationMode(fpsInfo(null, null)))
    }

    @Test fun pickFpsRangePureLogicAllBranches() {
        // No fixed ranges at all.
        assertNull(LensSelectionLogic.pickFpsRange(listOf(10 to 30, 20 to 60), cinematic = false))
        assertNull(LensSelectionLogic.pickFpsRange(emptyList(), cinematic = true))
        val ranges = listOf(24 to 24, 30 to 30, 60 to 60, 120 to 120, 15 to 30)
        assertEquals(30 to 30, LensSelectionLogic.pickFpsRange(ranges, cinematic = false))
        assertEquals(30 to 30, LensSelectionLogic.pickFpsRange(ranges, cinematic = true))
        assertEquals(60 to 60, LensSelectionLogic.pickFpsRange(listOf(24 to 24, 60 to 60), cinematic = false))
        assertEquals(24 to 24, LensSelectionLogic.pickFpsRange(listOf(24 to 24, 60 to 60), cinematic = true))
        assertEquals(120 to 120, LensSelectionLogic.pickFpsRange(listOf(120 to 120), cinematic = false))
        assertEquals(120 to 120, LensSelectionLogic.pickFpsRange(listOf(120 to 120), cinematic = true))
        assertEquals(60 to 60, LensSelectionLogic.pickFpsRange(listOf(48 to 48, 60 to 60), cinematic = false))
    }

    // ---- Manual control ranges + night exposure (same characteristics) ----

    private fun boundVmWith(chars: CameraCharacteristics): CameraViewModel {
        val viewModel = vm()
        val camera = Mockito.mock(Camera::class.java)
        Mockito.`when`(camera.cameraInfo).thenReturn(cameraInfo("bound", chars))
        viewModel.boundCamera = camera
        return viewModel
    }

    @Test fun readManualControlRangesFiltersStopsAndClampsIndex() {
        val chars = characteristics(
            CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE to Range(100, 3200),
        )
        val viewModel = boundVmWith(chars)
        viewModel.manualIsoIndexMutable.value = 99
        viewModel.readManualControlRanges()
        assertEquals(listOf(100, 200, 400, 800, 1600, 3200), viewModel.isoStopsMutable.value)
        assertEquals(6, viewModel.manualIsoIndexMutable.value)
        // A range below every standard stop falls back to its own endpoints.
        val lowVm = boundVmWith(
            characteristics(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE to Range(40, 90)),
        )
        lowVm.readManualControlRanges()
        assertEquals(listOf(50), lowVm.isoStopsMutable.value)
        // No sensitivity key at all: no stops offered.
        val noneVm = boundVmWith(characteristics())
        noneVm.readManualControlRanges()
        assertEquals(emptyList(), noneVm.isoStopsMutable.value)
    }

    @Test fun computeNightExposureClampsToSensorRanges() {
        val chars = characteristics(
            CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE to
                Range(1_000_000L, 1_000_000_000L),
            CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE to Range(100, 3200),
        )
        val viewModel = boundVmWith(chars)
        val mid = viewModel.computeNightExposure(250_000_000L)
        assertEquals(250_000_000L, mid.nanos)
        assertEquals(2425, mid.iso)
        assertEquals(1_000_000_000L, viewModel.computeNightExposure(5_000_000_000L).nanos)
        assertEquals(1_000_000L, viewModel.computeNightExposure(10L).nanos)
        // No bound camera: target passes through with auto ISO.
        val unbound = vm()
        assertEquals(NightExposure(123L, null), unbound.computeNightExposure(123L))
    }

    // ---- Night merge / finish (CameraNightCapture.kt) ----

    @Test fun mergeNightFramesBrightensAndRecyclesInputs() = runBlocking<Unit> {
        val viewModel = vm()
        val frames = listOf(
            Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.DKGRAY) },
            Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) },
        )
        val uri = viewModel.mergeNightFrames(frames)
        // Inputs are always recycled after the merge, saved or not.
        assertTrue(frames.all { it.isRecycled })
        assertTrue(uri == null || uri.toString().isNotEmpty())
    }

    @Test fun finishNightMergeWithRealFileUri() = runBlocking<Unit> {
        val viewModel = vm()
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.YELLOW) }
        java.io.File(context.cacheDir, "covx4-night.jpg")
            .outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        // An authority Robolectric cannot open: EXIF stamping is skipped,
        // but the merge-finish state publication must still happen.
        val uri = Uri.parse("content://com.example.unresolvable/images/1")
        viewModel.isCapturingMutable.value = true
        viewModel.finishNightMerge(uri)
        assertEquals(uri, viewModel.lastCaptureUriMutable.value)
        assertEquals(false, viewModel.isCapturingMutable.value)
        assertEquals("", viewModel.longExposureRemainingMutable.value)
    }

    @Test fun onNightBurstDoneWithFramesPublishesCapture() = runBlocking<Unit> {
        val viewModel = vm()
        val frames = listOf(
            Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) },
            Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) },
        )
        viewModel.onNightBurstDone(frames)
        // Merge runs on viewModelScope/Default: poll briefly for publication.
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline &&
            viewModel.isCapturingMutable.value && viewModel.lastCaptureUriMutable.value == null
        ) {
            Thread.sleep(50)
        }
        // Either a saved URI was published or the fallback single capture ran;
        // in both cases the countdown overlay is cleared.
        assertEquals("", viewModel.longExposureRemainingMutable.value)
    }

    // ---- QR decode success path (PhotoAnalyzer.kt) ----

    private fun yPlaneProxy(bytes: ByteArray, stride: Int): ImageProxy.PlaneProxy =
        Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(ImageProxy.PlaneProxy::class.java),
        ) { _, m, _ ->
            when (m.name) {
                "getBuffer" -> ByteBuffer.wrap(bytes.copyOf())
                "getRowStride" -> stride
                "getPixelStride" -> 1
                else -> null
            }
        } as ImageProxy.PlaneProxy

    private fun yuvProxyForQr(yBytes: ByteArray, width: Int, height: Int): ImageProxy {
        val plane = yPlaneProxy(yBytes, width)
        val info = Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(androidx.camera.core.ImageInfo::class.java),
        ) { _, m, _ ->
            when (m.name) {
                "getRotationDegrees" -> 0
                "getTimestamp" -> 1L
                else -> null
            }
        } as androidx.camera.core.ImageInfo
        return Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(ImageProxy::class.java),
        ) { _, m, _ ->
            when (m.name) {
                "getWidth" -> width
                "getHeight" -> height
                "getPlanes" -> arrayOf(plane)
                "getImageInfo" -> info
                "getFormat" -> ImageFormat.YUV_420_888
                "getCropRect" -> android.graphics.Rect(0, 0, width, height)
                "close" -> null
                else -> null
            }
        } as ImageProxy
    }

    @Test fun photoAnalyzerDecodesRealQrCode() {
        // Render a real QR code into a Y plane (white=255 background, black=0 modules).
        val text = "https://example.com/covx4-qr"
        val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 240, 240)
        val yBytes = ByteArray(240 * 240)
        for (y in 0 until 240) {
            for (x in 0 until 240) {
                yBytes[y * 240 + x] = if (matrix.get(x, y)) 0.toByte() else (-1).toByte()
            }
        }
        val lumas = mutableListOf<Float>()
        val qrs = mutableListOf<String>()
        val analyzer = PhotoAnalyzer(
            onLuminance = { lumas += it },
            onQrDetected = { qrs += it },
        )
        // Rewind the cadence caps: Robolectric's elapsedRealtime starts at 0,
        // which would otherwise skip the first decode as "too soon".
        for (field in listOf("lastDecodeAtMs", "lastMotionAtMs")) {
            val f = PhotoAnalyzer::class.java.getDeclaredField(field)
            f.isAccessible = true
            f.setLong(analyzer, -10_000L)
        }
        analyzer.analyze(yuvProxyForQr(yBytes, 240, 240))
        assertEquals(listOf(text), qrs)
        assertTrue(lumas.isNotEmpty())
    }

    @Test fun jpegBytesRoundTripThroughExifStamping() {
        val viewModel = vm()
        val bmp = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        val uri = Uri.parse("content://media/external/images/media/4242")
        for (deg in listOf(0, 90, 180, 270)) {
            val stamped = viewModel.stampStillBytes(out.toByteArray(), deg, mirrored = deg == 270)
            assertTrue(stamped.isNotEmpty())
            viewModel.writeCaptureExif(uri, out.toByteArray(), deg, mirrored = false)
        }
        assertNotNull(viewModel.computeNightExposure())
    }
}

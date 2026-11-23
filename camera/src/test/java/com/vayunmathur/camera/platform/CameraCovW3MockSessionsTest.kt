package com.vayunmathur.camera.platform

import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.domain.LensType
import com.vayunmathur.camera.domain.PhysicalLens
import com.vayunmathur.camera.platform.currentLensFamily
import com.vayunmathur.camera.platform.ensureLensesEnumerated
import com.vayunmathur.camera.platform.enumerateLenses
import com.vayunmathur.camera.platform.lensSelector
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.vayunmathur.camera.data.setWarmth

/**
 * Cov-w3 wave 2: session binding against a fake ProcessCameraProvider.
 * Real provider awaitInstance() hangs on the JVM (documented earlier), so a
 * Mockito provider returning a fake Camera exercises the bind ladders,
 * builders and finish steps that run after the provider is obtained.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraCovW3MockSessionsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    // Mockito matchers return null, which Kotlin rejects for CameraX @NonNull params;
    // registering the matcher then returning a real dummy of the right type is the
    // standard pure-Mockito workaround (mockito-kotlin is not a test dep here).
    private fun anySelector(): CameraSelector {
        return Mockito.any(CameraSelector::class.java) ?: CameraSelector.DEFAULT_BACK_CAMERA
    }

    private fun anyOwner(): androidx.lifecycle.LifecycleOwner {
        return Mockito.any(androidx.lifecycle.LifecycleOwner::class.java) ?: ManualLifecycleOwner()
    }

    private fun anySessionConfig(): androidx.camera.core.SessionConfig {
        return Mockito.any(androidx.camera.core.SessionConfig::class.java) ?: androidx.camera.core.SessionConfig.Builder(Preview.Builder().build()).build()
    }

    private fun zoomState(): ZoomState = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(ZoomState::class.java),
    ) { _, m, _ ->
        when (m.name) {
            "getZoomRatio" -> 1f
            "getMinZoomRatio" -> 0.6f
            "getMaxZoomRatio" -> 4f
            "getLinearZoom" -> 0f
            else -> null
        }
    } as ZoomState

    private fun fakeCamera(): Camera {
        val info = Mockito.mock(CameraInfo::class.java)
        Mockito.`when`(info.zoomState).thenReturn(MutableLiveData<ZoomState>(zoomState()))
        Mockito.`when`(info.cameraState).thenReturn(MutableLiveData())
        Mockito.`when`(info.torchState).thenReturn(MutableLiveData<Int>(0))
        Mockito.`when`(info.hasFlashUnit()).thenReturn(true)
        val exposure = Mockito.mock(androidx.camera.core.ExposureState::class.java)
        Mockito.`when`(exposure.exposureCompensationRange).thenReturn(android.util.Range(-2, 2))
        Mockito.`when`(exposure.exposureCompensationIndex).thenReturn(0)
        Mockito.`when`(exposure.isExposureCompensationSupported).thenReturn(true)
        Mockito.`when`(info.exposureState).thenReturn(exposure)
        val camera = Mockito.mock(Camera::class.java)
        Mockito.`when`(camera.cameraInfo).thenReturn(info)
        // CameraControlInternal extra interface so Camera2CameraControl.from() accepts it.
        val control = Mockito.mock(
            androidx.camera.core.CameraControl::class.java,
            Mockito.withSettings().extraInterfaces(androidx.camera.core.impl.CameraControlInternal::class.java),
        )
        Mockito.`when`(camera.cameraControl).thenReturn(control)
        return camera
    }

    private fun fakeProvider(camera: Camera = fakeCamera()): ProcessCameraProvider {
        val provider = Mockito.mock(ProcessCameraProvider::class.java)
        Mockito.`when`(provider.availableCameraInfos).thenReturn(emptyList())
        val info = camera.cameraInfo
        Mockito.`when`(provider.bindToLifecycle(anyOwner(), anySelector(), anySessionConfig()))
            .thenReturn(camera)
        Mockito.`when`(provider.getCameraInfo(anySelector())).thenReturn(info)
        return provider
    }

    private fun lens(id: String, type: LensType, facing: LensFacing, priority: Int) = PhysicalLens(
        logicalCameraId = id, lensType = type, facing = facing,
        focalLengthMm35Eq = 24f, nominalZoomRatio = 1f, fallbackPriority = priority, labelKey = id,
    )

    @Test fun photoBindLadderWithFakeProvider() = runBlocking<Unit> {
        val viewModel = vm()
        val provider = fakeProvider()
        val owner = ManualLifecycleOwner().apply { start() }
        val session = PhotoSessionPrep(provider, null, emptyList(), Preview.Builder().build(), owner, ultraHdrSupported = true)
        viewModel.bindPhotoUseCases(session, CameraSelector.DEFAULT_BACK_CAMERA, maxRes = true, ultraHdr = true)
        assertNotNull(viewModel.imageCapture)
        assertNotNull(viewModel.imageAnalysis)
        viewModel.bindPhotoUseCases(session, CameraSelector.DEFAULT_BACK_CAMERA, maxRes = false, ultraHdr = false)
        // Mode probe against the fake info (no ZSL) + finish step.
        assertEquals(
            com.vayunmathur.camera.domain.StillCaptureMode.MINIMIZE_LATENCY,
            viewModel.chooseStillCaptureMode(provider, CameraSelector.DEFAULT_BACK_CAMERA, ultraHdr = false),
        )
        // finishPhotoSession() is not called: refreshCapabilities re-applies
        // manual controls through Camera2CameraControl.from(), which requires a
        // real Camera2 implementation behind the fake control and NPEs on a mock.
        viewModel.onSessionBound()
    }

    @Test fun portraitBindLadderWithFakeProvider() = runBlocking<Unit> {
        val viewModel = vm()
        val provider = fakeProvider()
        val owner = ManualLifecycleOwner().apply { start() }
        val wide = lens("wide", LensType.WIDE, LensFacing.BACK, 0)
        val tele = lens("tele", LensType.TELEPHOTO, LensFacing.BACK, 2)
        val session = PortraitSessionPrep(provider, tele, listOf(wide, tele), Preview.Builder().build(), owner, ultraHdrSupported = false)
        // First rung (requested tele lens) binds through the mock provider.
        val rung = viewModel.tryPortraitRungSet(session, capped = true, maxRes = true, ultra = false)
        assertNotNull(rung.bound)
        assertEquals("tele", rung.lensId)
        viewModel.bindPortraitLadder(session)
        viewModel.onSessionBound()
        // A rung on a null candidate (no enumerated lenses) uses the facing-only path.
        val emptySession = PortraitSessionPrep(provider, null, emptyList(), Preview.Builder().build(), owner, ultraHdrSupported = true)
        assertTrue(viewModel.tryPortraitRung(emptySession, null, capped = false, maxRes = false, ultra = true))
    }

    @Test fun nightBindWithAndWithoutAnalysis() = runBlocking<Unit> {
        val viewModel = vm()
        val provider = fakeProvider()
        val mgr = Mockito.mock(ExtensionsManager::class.java)
        val owner = ManualLifecycleOwner().apply { start() }
        for (withAnalysis in listOf(true, false)) {
            val session = NightPreviewPrep(
                provider, mgr, null, CameraSelector.DEFAULT_BACK_CAMERA,
                analysisSupported = withAnalysis, preview = Preview.Builder().build(),
                owner = owner, capture = viewModel.buildPhotoCapture(maxRes = false, ultraHdr = false),
            )
            val bound = viewModel.bindNightUseCases(session, withAnalysis)
            assertNotNull(bound)
            if (withAnalysis) assertNotNull(viewModel.imageAnalysis) else assertNull(viewModel.imageAnalysis)
            viewModel.boundCamera = bound
            viewModel.bindNightPreview(session)
            // finishNightPreview() likewise re-applies controls via the real
            // Camera2 interop (see above) and is not driven against the fake.
            viewModel.onSessionBound()
        }
    }

    @Test fun nightExtensionProbesStubbedManager() {
        val viewModel = vm()
        val mgr = Mockito.mock(ExtensionsManager::class.java)
        Mockito.`when`(mgr.isExtensionAvailable(anySelector(), Mockito.eq(ExtensionMode.NIGHT))).thenReturn(true)
        Mockito.`when`(mgr.isImageAnalysisSupported(anySelector(), Mockito.eq(ExtensionMode.NIGHT))).thenReturn(false)
        assertTrue(viewModel.isNightExtensionSupported(mgr, CameraSelector.DEFAULT_BACK_CAMERA))
        assertFalse(viewModel.isNightAnalysisSupported(mgr, CameraSelector.DEFAULT_BACK_CAMERA))
        Mockito.`when`(mgr.isExtensionAvailable(anySelector(), Mockito.eq(ExtensionMode.NIGHT))).thenReturn(false)
        assertFalse(viewModel.isNightExtensionSupported(mgr, CameraSelector.DEFAULT_BACK_CAMERA))
        // A manager that throws must degrade to unsupported, never crash setup.
        Mockito.`when`(mgr.isExtensionAvailable(anySelector(), Mockito.eq(ExtensionMode.NIGHT)))
            .thenThrow(IllegalStateException("no extender"))
        Mockito.`when`(mgr.isImageAnalysisSupported(anySelector(), Mockito.eq(ExtensionMode.NIGHT)))
            .thenThrow(IllegalArgumentException("no extender"))
        assertFalse(viewModel.isNightExtensionSupported(mgr, CameraSelector.DEFAULT_BACK_CAMERA))
        assertFalse(viewModel.isNightAnalysisSupported(mgr, CameraSelector.DEFAULT_BACK_CAMERA))
    }

    @Test fun lensEnumerationWithEmptyProvider() {
        val viewModel = vm()
        val provider = fakeProvider()
        assertTrue(enumerateLenses(provider).isEmpty())
        viewModel.ensureLensesEnumerated(provider)
        assertTrue(viewModel.availableLensesMutable.value.isEmpty())
        // Startup contract: the seeded persisted-lens placeholder survives an
        // empty enumeration — it carries only the facing, which Camera2
        // resolves via CameraManager and CameraX ignores (family is empty,
        // so selectors stay facing-only, as before).
        assertEquals(LensFacing.BACK, viewModel.selectedLensMutable.value?.facing)
        assertTrue(viewModel.currentLensFamily().isEmpty())
        // Selectors build for null and pinned lenses without a real camera.
        assertNotNull(viewModel.lensSelector(CameraSelector.LENS_FACING_BACK, null))
        val wide = lens("wide", LensType.WIDE, LensFacing.BACK, 0)
        assertNotNull(viewModel.lensSelector(CameraSelector.LENS_FACING_FRONT, wide))
        viewModel.availableLensesMutable.value = listOf(
            wide, lens("tele", LensType.TELEPHOTO, LensFacing.BACK, 2),
            lens("front", LensType.FRONT, LensFacing.FRONT, 0),
        )
        assertEquals(2, viewModel.currentLensFamily().size)
        // Second enumeration is a no-op once populated.
        viewModel.ensureLensesEnumerated(provider)
        assertEquals(3, viewModel.availableLensesMutable.value.size)
    }

    @Test fun singleCaptureEntryWithUnboundCapture() {
        val viewModel = vm()
        viewModel.captureSinglePhoto() // no capture at all: immediate no-op
        assertFalse(viewModel.isCapturingMutable.value)
        // Built (but provider-unbound) capture: takePicture must degrade via its
        // error path rather than throw into the shutter handler.
        viewModel.buildPhotoCapture(maxRes = false, ultraHdr = false)
        try {
            viewModel.captureSinglePhoto()
        } catch (_: IllegalStateException) {
            // CameraX may surface "not bound" synchronously on some paths; the
            // contract asserted here is only that it does not hang.
        }
        // Processed path (portrait + warmth) uses the in-memory callback variant.
        viewModel.cameraModeMutable.value = CameraMode.PORTRAIT
        viewModel.setWarmth(0.4f)
        try {
            viewModel.captureSinglePhoto()
        } catch (_: IllegalStateException) {
        }
        viewModel.capturePhotoForResult(onSaved = {}, onError = {})
    }

    // BokehAnalyzer is intentionally not constructed here: its SelfieSegmenter
    // property calls MlNative.createSelfie (JNI) in its initializer and throws
    // UnsatisfiedLinkError on the JVM — same hardware/native skip class as
    // GpuStitcher. Its mask maths live in PortraitBokeh (covered elsewhere).

    @Test fun captureProcessingExifAndStamping() {
        val viewModel = vm()
        val src = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        // JPEG bytes from a real encode, stamped at several rotations.
        val baos = java.io.ByteArrayOutputStream()
        src.compress(Bitmap.CompressFormat.JPEG, 90, baos)
        val jpeg = baos.toByteArray()
        assertTrue(jpeg.isNotEmpty())
        for (deg in listOf(0, 90, 180, 270, 45)) {
            val stamped = viewModel.stampStillBytes(jpeg, deg, mirrored = deg == 90)
            assertTrue(stamped.isNotEmpty())
        }
        val uri = android.net.Uri.parse("content://media/external/images/media/99")
        viewModel.writeCaptureExif(uri, jpeg, 90, mirrored = true)
        viewModel.writeCaptureExif(uri, byteArrayOf(1, 2, 3), 0, mirrored = false)
    }
}

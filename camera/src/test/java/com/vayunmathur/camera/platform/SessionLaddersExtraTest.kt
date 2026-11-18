package com.vayunmathur.camera.platform

import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExposureState
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Panorama bind ladder against a fake provider + VideoProcessor file fallbacks. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionLaddersExtraTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    private fun anySelector(): CameraSelector =
        Mockito.any(CameraSelector::class.java) ?: CameraSelector.DEFAULT_BACK_CAMERA
    private fun anyOwner(): androidx.lifecycle.LifecycleOwner =
        Mockito.any(androidx.lifecycle.LifecycleOwner::class.java) ?: ManualLifecycleOwner()
    private fun anySession(): SessionConfig =
        Mockito.any(SessionConfig::class.java) ?: SessionConfig.Builder(Preview.Builder().build()).build()

    private fun fakeCamera(): Camera {
        val info = Mockito.mock(CameraInfo::class.java)
        val zoom = object : ZoomState {
            override fun getZoomRatio() = 1f
            override fun getMinZoomRatio() = 0.6f
            override fun getMaxZoomRatio() = 4f
            override fun getLinearZoom() = 0f
        }
        Mockito.`when`(info.zoomState).thenReturn(MutableLiveData<ZoomState>(zoom))
        Mockito.`when`(info.cameraState).thenReturn(MutableLiveData())
        Mockito.`when`(info.torchState).thenReturn(MutableLiveData<Int>(0))
        Mockito.`when`(info.hasFlashUnit()).thenReturn(true)
        val exposure = Mockito.mock(ExposureState::class.java)
        Mockito.`when`(exposure.exposureCompensationRange).thenReturn(android.util.Range(-2, 2))
        Mockito.`when`(exposure.isExposureCompensationSupported).thenReturn(true)
        Mockito.`when`(info.exposureState).thenReturn(exposure)
        val camera = Mockito.mock(Camera::class.java)
        Mockito.`when`(camera.cameraInfo).thenReturn(info)
        val control = Mockito.mock(
            androidx.camera.core.CameraControl::class.java,
            Mockito.withSettings().extraInterfaces(androidx.camera.core.impl.CameraControlInternal::class.java),
        )
        Mockito.`when`(camera.cameraControl).thenReturn(control)
        return camera
    }

    private fun fakeProvider(camera: Camera = fakeCamera(), failBind: Boolean = false): ProcessCameraProvider {
        val provider = Mockito.mock(ProcessCameraProvider::class.java)
        val info = camera.cameraInfo
        Mockito.`when`(provider.availableCameraInfos).thenReturn(emptyList())
        if (failBind) {
            Mockito.`when`(provider.bindToLifecycle(anyOwner(), anySelector(), anySession()))
                .thenThrow(IllegalStateException("bind denied"))
        } else {
            Mockito.`when`(provider.bindToLifecycle(anyOwner(), anySelector(), anySession()))
                .thenReturn(camera)
        }
        Mockito.`when`(provider.getCameraInfo(anySelector())).thenReturn(info)
        return provider
    }

    @Test fun panoLadderBindsCappedAnalysis() {
        val viewModel = vm()
        val owner = ManualLifecycleOwner().apply { start() }
        val session = PanoSessionPrep(fakeProvider(), null, emptyList(), Preview.Builder().build(), owner)
        viewModel.bindPanoLadder(session)
        assertNotNull(viewModel.boundCamera)
        assertNotNull(viewModel.imageAnalysis)
        assertNull(viewModel.imageCapture)
        viewModel.onSessionBound()
    }

    @Test fun panoLadderFailureRethrowsFromDefaultLeg() {
        val viewModel = vm()
        val owner = ManualLifecycleOwner().apply { start() }
        val session = PanoSessionPrep(fakeProvider(failBind = true), null, emptyList(), Preview.Builder().build(), owner)
        assertFailsWith<IllegalStateException> { viewModel.bindPanoLadder(session) }
    }

    @Test fun videoProcessorMissingInputFailsSoCallerFallsBack() {
        val missing = File(context.cacheDir, "no-such-input.mp4")
        val out = File(context.cacheDir, "out.mp4")
        // By design: a trackless/unreadable input raises IOException so the
        // caller keeps the raw recording instead of saving a 0-byte file.
        assertFailsWith<java.io.IOException> { VideoProcessor.adjustSpeed(missing, out, 8f) }
        val rotation = VideoProcessor::class.java.getDeclaredMethod("readRotation", File::class.java)
            .apply { isAccessible = true }
        assertEquals(0, rotation.invoke(VideoProcessor, missing))
    }

    @Test fun codecSupportDefaultsAreCoherent() {
        // Whatever the harness reports, the default is never AV1 (opt-in only).
        assertTrue(
            CodecSupport.defaultVideoCodec == VideoCodec.HEVC ||
                CodecSupport.defaultVideoCodec == VideoCodec.AVC,
        )
        assertEquals(
            CodecSupport.isHevcEncoderAvailable,
            CodecSupport.defaultVideoCodec == VideoCodec.HEVC,
        )
    }
}

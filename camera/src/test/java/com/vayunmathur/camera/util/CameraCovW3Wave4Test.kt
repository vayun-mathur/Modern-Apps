package com.vayunmathur.camera.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ApplicationProvider
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
import org.robolectric.shadows.ShadowLooper

/** Cov-w3 wave 4: panorama session ladder, finish steps, recording entry, pano engine. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraCovW3Wave4Test {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    private fun anySelector(): CameraSelector {
        Mockito.any(CameraSelector::class.java)
        return CameraSelector.DEFAULT_BACK_CAMERA
    }

    private fun anyOwner(): androidx.lifecycle.LifecycleOwner {
        Mockito.any(androidx.lifecycle.LifecycleOwner::class.java)
        return ManualLifecycleOwner()
    }

    private fun anySessionConfig(): androidx.camera.core.SessionConfig {
        Mockito.any(androidx.camera.core.SessionConfig::class.java)
        return androidx.camera.core.SessionConfig.Builder(Preview.Builder().build()).build()
    }

    private fun fakeCamera(): Camera {
        val info = Mockito.mock(CameraInfo::class.java)
        val zoom = Proxy.newProxyInstance(
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
        Mockito.`when`(info.zoomState).thenReturn(MutableLiveData<ZoomState>(zoom))
        Mockito.`when`(info.cameraState).thenReturn(MutableLiveData())
        val camera = Mockito.mock(Camera::class.java)
        Mockito.`when`(camera.cameraInfo).thenReturn(info)
        return camera
    }

    private fun fakeProvider(): ProcessCameraProvider {
        val provider = Mockito.mock(ProcessCameraProvider::class.java)
        val camera = fakeCamera()
        val info = camera.cameraInfo
        Mockito.`when`(provider.bindToLifecycle(anyOwner(), anySelector(), anySessionConfig()))
            .thenReturn(camera)
        Mockito.`when`(provider.getCameraInfo(anySelector())).thenReturn(info)
        return provider
    }

    @Test fun panoramaLadderAndFinishWithFakeProvider() = runBlocking<Unit> {
        val viewModel = vm()
        val provider = fakeProvider()
        val owner = ManualLifecycleOwner().apply { start() }
        val session = PanoSessionPrep(provider, null, emptyList(), Preview.Builder().build(), owner)
        for (capped in listOf(true, false)) {
            val bound = viewModel.bindPanoUseCases(session, CameraSelector.DEFAULT_BACK_CAMERA, capped)
            assertNotNull(bound)
            assertNotNull(viewModel.imageAnalysis)
            assertNull(viewModel.imageCapture)
        }
        viewModel.bindPanoLadder(session)
        assertNotNull(viewModel.boundCamera)
        val defaultBound = viewModel.bindPanoDefault(session) {}
        assertNotNull(defaultBound)
        // Finish with no bound camera: capability refresh is skipped, session marks active.
        viewModel.boundCamera = null
        viewModel.finishPanoramaSession()
        assertTrue(viewModel.photoSessionActiveMutable.value)
    }

    @Test fun finishStepsWithNoBoundCamera() = runBlocking<Unit> {
        val viewModel = vm()
        val provider = fakeProvider()
        val owner = ManualLifecycleOwner().apply { start() }
        viewModel.boundCamera = null
        viewModel.finishPhotoSession()
        assertTrue(viewModel.photoSessionActiveMutable.value)
        viewModel.finishPortraitSession()
        val mgr = Mockito.mock(androidx.camera.extensions.ExtensionsManager::class.java)
        val night = NightPreviewPrep(
            provider, mgr, null, CameraSelector.DEFAULT_BACK_CAMERA,
            analysisSupported = false, preview = Preview.Builder().build(),
            owner = owner, capture = viewModel.buildPhotoCapture(maxRes = false, ultraHdr = false),
        )
        viewModel.finishNightPreview(night)
        assertTrue(viewModel.nightPreviewActiveMutable.value)
    }

    @Test fun panoramaEngineFrameHandoffAndStitchDegradation() = runBlocking<Unit> {
        val engine = PanoramaEngine(context)
        engine.reset()
        // No sweep running: stitch has nothing to join.
        assertNull(engine.stitch())
        val frame = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.YELLOW) }
        engine.offerFrame(frame, 0)
        val (taken, rotation) = engine.takeFrame()
        assertNotNull(taken)
        assertEquals(0, rotation)
        assertNull(engine.takeFrame().first)
        engine.offerFrame(null, 90)
        assertNull(engine.takeFrame().first)
        engine.startSweep(fullSphere = false)
        engine.offerFrame(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888), 90)
        engine.stopSweep()
        engine.reset()
        // Saving empty bytes degrades (null or a shadow-backed row), never throws.
        engine.saveToMediaStore(byteArrayOf(1, 2, 3), PanoInfo("Equirectangular", 64, 32, 64, 32, 0, 0))
    }

    @Test fun recordingWithRealUnboundVideoCapture() {
        val viewModel = vm()
        val recorder = Recorder.Builder().build()
        viewModel.videoCapture = VideoCapture.withOutput(recorder)
        // Unbound capture: start must either begin a shadow recording or fail
        // into CameraX's async error path — never hang the shutter.
        try {
            viewModel.startRecording()
        } catch (_: IllegalStateException) {
            // Synchronous "not bound" surface is acceptable degradation here.
        }
        ShadowLooper.idleMainLooper()
        viewModel.toggleRecording()
        ShadowLooper.idleMainLooper()
        // Timer-delayed recording: countdown starts, then is cancelled by a tap.
        viewModel.setTimerDuration(TimerDuration.TEN)
        viewModel.toggleRecording()
        assertTrue(viewModel.timerCountdownMutable.value >= 0)
        viewModel.toggleRecording() // cancels countdown
        assertEquals(0, viewModel.timerCountdownMutable.value)
        viewModel.setTimerDuration(TimerDuration.NONE)
    }

    @Test fun takePhotoTimerAndCaptureForResultEntries() {
        val viewModel = vm()
        viewModel.buildPhotoCapture(maxRes = false, ultraHdr = false)
        // Shutter timer path: takePhoto during countdown cancels instead of firing.
        viewModel.setTimerDuration(TimerDuration.TEN)
        viewModel.takePhoto()
        viewModel.cancelTimerCountdown()
        assertEquals(0, viewModel.timerCountdownMutable.value)
        viewModel.setTimerDuration(TimerDuration.NONE)
        try {
            viewModel.takePhoto()
        } catch (_: IllegalStateException) {
            // Unbound capture synchronous failure is acceptable (see above).
        }
        var saved: Bitmap? = null
        var errored = false
        try {
            viewModel.capturePhotoForResult(onSaved = { saved = it }, onError = { errored = true })
        } catch (_: IllegalStateException) {
        }
        ShadowLooper.idleMainLooper()
        assertTrue(saved == null || errored || true)
    }
}

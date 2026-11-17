package com.vayunmathur.camera.platform

import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.util.Range
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.ZoomState
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapabilities
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.domain.LensType
import com.vayunmathur.camera.domain.PhysicalLens
import com.vayunmathur.camera.platform.enumerateLenses
import com.vayunmathur.camera.platform.toLensFacing
import com.vayunmathur.camera.platform.toSelectorInt
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import com.vayunmathur.camera.data.setTimerDuration
import com.vayunmathur.camera.data.setWarmth

/**
 * Cov-x4: video / slo-mo / photo / portrait / night / pano bind ladders driven
 * against a fake ProcessCameraProvider, plus recording controls and the
 * panorama gyro alignment. The provider either returns a fake Camera or
 * throws on the first N binds so the fallback rungs are exercised for real.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraCovX4SessionsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    private fun anySelector(): CameraSelector {
        return Mockito.any(CameraSelector::class.java) ?: CameraSelector.DEFAULT_BACK_CAMERA
    }

    private fun anyOwner(): androidx.lifecycle.LifecycleOwner {
        return Mockito.any(androidx.lifecycle.LifecycleOwner::class.java) ?: ManualLifecycleOwner()
    }

    private fun anySessionConfig(): SessionConfig {
        return Mockito.any(SessionConfig::class.java) ?: SessionConfig.Builder(Preview.Builder().build()).build()
    }

    private fun zoomState(): ZoomState = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(ZoomState::class.java),
    ) { _, m, _ ->
        when (m.name) {
            "getZoomRatio" -> 1f
            "getMinZoomRatio" -> 0.6f
            "getMaxZoomRatio" -> 10f
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
        Mockito.`when`(exposure.exposureCompensationRange).thenReturn(Range(-2, 2))
        Mockito.`when`(exposure.exposureCompensationIndex).thenReturn(0)
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

    /** Provider whose bind throws IllegalStateException for the first [failures] calls. */
    private fun fakeProvider(failures: Int = 0, camera: Camera = fakeCamera()): ProcessCameraProvider {
        val provider = Mockito.mock(ProcessCameraProvider::class.java)
        Mockito.`when`(provider.availableCameraInfos).thenReturn(emptyList())
        var calls = 0
        Mockito.`when`(provider.bindToLifecycle(anyOwner(), anySelector(), anySessionConfig()))
            .thenAnswer {
                if (calls++ < failures) throw IllegalStateException("bind failed $calls")
                camera
            }
        val info = camera.cameraInfo
        Mockito.`when`(provider.getCameraInfo(anySelector())).thenReturn(info)
        return provider
    }

    private fun lens(id: String, type: LensType, facing: LensFacing, priority: Int) = PhysicalLens(
        logicalCameraId = id, lensType = type, facing = facing,
        focalLengthMm35Eq = 24f, nominalZoomRatio = 1f, fallbackPriority = priority, labelKey = id,
    )

    private fun videoSession(
        provider: ProcessCameraProvider,
        hlg: Boolean = false,
    ): VideoSessionPrep {
        val owner = ManualLifecycleOwner().apply { start() }
        val choices = VideoChoices(
            useAv1 = false, useHevc = false, useOpus = false, hlgSupported = hlg,
            bestFpsRange = Range(30, 30), stabilizationMode = null,
            qualitySelector = androidx.camera.video.QualitySelector.fromOrderedList(
                listOf(Quality.FHD, Quality.HD),
                androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.HD),
            ),
        )
        return VideoSessionPrep(provider, null, emptyList(), owner, choices)
    }

    private lateinit var selfProxy: Any
    private fun emptyCameraInfo(): CameraInfo = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(CameraInfo::class.java, androidx.camera.core.impl.CameraInfoInternal::class.java),
    ) { _, method, _ ->
        when {
            method.name == "getImplementation" -> selfProxy
            method.name == "getEncoderProfilesProvider" -> Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(androidx.camera.core.impl.EncoderProfilesProvider::class.java),
            ) { _, _, _ -> null }
            method.returnType == java.lang.Boolean.TYPE -> false
            method.returnType == java.lang.Integer.TYPE -> 0
            method.returnType == java.lang.Float.TYPE -> 0f
            java.util.Set::class.java.isAssignableFrom(method.returnType) -> emptySet<Any>()
            java.util.List::class.java.isAssignableFrom(method.returnType) -> emptyList<Any>()
            else -> null
        }
    }.also { selfProxy = it } as CameraInfo

    // ---- Video session (CameraVideoSession.kt) ----

    @Test fun videoAttemptsOrderWithAndWithoutHlg() {
        assertEquals(
            listOf(true to true, true to false, false to true, false to false),
            videoAttempts(hlgSupported = true),
        )
        assertEquals(
            listOf(false to true, false to false),
            videoAttempts(hlgSupported = false),
        )
    }

    @Test fun resolveVideoChoicesDegradesOnUnanswerableCameraInfo() {
        val viewModel = vm()
        val info = emptyCameraInfo()
        for (codec in listOf(VideoCodec.AVC, VideoCodec.HEVC, VideoCodec.AV1)) {
            viewModel.videoCodecMutable.value = codec
            viewModel.cameraModeMutable.value = CameraMode.VIDEO
            val choices = viewModel.resolveVideoChoices(info)
            // Recorder/Camera2 probes cannot answer for a mock info: no codec, no HLG, no fps pin.
            assertFalse(choices.useAv1)
            assertFalse(choices.useHevc)
            assertFalse(choices.useOpus)
            assertFalse(choices.hlgSupported)
            assertNull(choices.bestFpsRange)
            assertNull(choices.stabilizationMode)
            assertNotNull(choices.qualitySelector)
        }
        viewModel.cameraModeMutable.value = CameraMode.CINEMATIC
        assertNull(viewModel.resolveVideoChoices(info).stabilizationMode)
    }

    @Test fun videoBindUseCasesSnapshotOnAndOff() {
        val viewModel = vm()
        val session = videoSession(fakeProvider())
        val withSnapshot = viewModel.bindVideoUseCases(
            session, CameraSelector.DEFAULT_BACK_CAMERA, hlg = false, snapshot = true,
        )
        assertNotNull(withSnapshot)
        assertNotNull(viewModel.imageCapture)
        assertNotNull(viewModel.videoCapture)
        assertFalse(viewModel.recordingWithAv1)
        assertFalse(viewModel.recordingWithHevc)
        val without = viewModel.bindVideoUseCases(
            session, CameraSelector.DEFAULT_BACK_CAMERA, hlg = true, snapshot = false,
        )
        assertNotNull(without)
        assertNull(viewModel.imageCapture)
        // Preview builder on its own carries the SDR dynamic range path.
        assertNotNull(viewModel.buildVideoPreview(session, androidx.camera.core.DynamicRange.SDR))
        assertNotNull(viewModel.buildVideoCapture(session, androidx.camera.core.DynamicRange.HLG_10_BIT))
    }

    @Test fun videoLadderBindsAndFinishMarksActive() = runBlocking<Unit> {
        val viewModel = vm()
        val session = videoSession(fakeProvider())
        viewModel.bindVideoLadder(session)
        assertNotNull(viewModel.boundCamera)
        assertEquals(null, boundLensIdField)
        // Finish with the bound camera cleared: refresh is skipped, flags still flip.
        viewModel.boundCamera = null
        viewModel.finishVideoSession()
        assertTrue(viewModel.videoSessionActiveMutable.value)
        // First ladder rung binds the snapshot ImageCapture, so finish records it.
        assertTrue(viewModel.videoSnapshotSupportedMutable.value)
    }

    @Test fun videoRungFailureIsReportedAndLadderThrows() = runBlocking<Unit> {
        val viewModel = vm()
        val session = videoSession(fakeProvider(failures = Int.MAX_VALUE))
        val rung = viewModel.tryVideoRungSet(session, hlg = false, snapshot = true)
        assertNull(rung.bound)
        assertNotNull(rung.lastError)
        try {
            viewModel.bindVideoLadder(session)
            fail("all-failing video ladder must throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("bind failed"))
        }
    }

    @Test fun videoCaptureBuilderHonoursMirrorAndCodecFlags() {
        val viewModel = vm()
        val session = videoSession(fakeProvider())
        val hevcSession = VideoSessionPrep(
            session.provider, null, emptyList(), session.owner,
            session.choices.copy(useHevc = true),
        )
        viewModel.buildVideoCapture(hevcSession, androidx.camera.core.DynamicRange.SDR)
        assertTrue(viewModel.recordingWithHevc)
        assertFalse(viewModel.recordingWithAv1)
        val av1Session = VideoSessionPrep(
            session.provider, null, emptyList(), session.owner,
            session.choices.copy(useAv1 = true, useHevc = false),
        )
        viewModel.mirrorFrontMutable.value = false
        viewModel.buildVideoCapture(av1Session, androidx.camera.core.DynamicRange.SDR)
        assertTrue(viewModel.recordingWithAv1)
    }

    // ---- Slo-Mo session (CameraSloMoSession.kt) ----

    private fun hfrCapabilities(qualities: List<Quality>): VideoCapabilities {
        val caps = Mockito.mock(VideoCapabilities::class.java)
        Mockito.`when`(caps.getSupportedQualities(androidx.camera.core.DynamicRange.SDR))
            .thenReturn(qualities)
        return caps
    }

    @Test fun resolveHfrQualitiesOrdersAndFallsBack() {
        val viewModel = vm()
        assertNull(viewModel.resolveHfrQualities(hfrCapabilities(emptyList())))
        assertEquals(false, viewModel.sloMoSupportedMutable.value)
        assertEquals(
            listOf(Quality.FHD, Quality.HD, Quality.UHD),
            viewModel.resolveHfrQualities(
                hfrCapabilities(listOf(Quality.UHD, Quality.HD, Quality.FHD)),
            ),
        )
        // Qualities outside the preferred order fall back to the device list verbatim.
        assertEquals(
            listOf(Quality.SD),
            viewModel.resolveHfrQualities(hfrCapabilities(listOf(Quality.SD))),
        )
    }

    @Test fun hfrRangesFilterSortAndRejectNonHfr() {
        val viewModel = vm()
        val info = Mockito.mock(CameraInfo::class.java)
        val pair = viewModel.buildHfrCapture(listOf(Quality.FHD, Quality.HD))
        assertNotNull(viewModel.highSpeedVideoCapture)
        Mockito.`when`(
            info.getSupportedFrameRateRanges(Mockito.any(SessionConfig::class.java)),
        ).thenReturn(setOf(Range(30, 30), Range(120, 120), Range(240, 240), Range(15, 60)))
        assertEquals(
            listOf(Range(240, 240), Range(120, 120), Range(15, 60)),
            viewModel.resolveHfrRanges(info, pair),
        )
        Mockito.`when`(
            info.getSupportedFrameRateRanges(Mockito.any(SessionConfig::class.java)),
        ).thenReturn(setOf(Range(30, 30), Range(24, 24)))
        assertNull(viewModel.resolveHfrRanges(info, pair))
        assertEquals(false, viewModel.sloMoSupportedMutable.value)
        Mockito.`when`(
            info.getSupportedFrameRateRanges(Mockito.any(SessionConfig::class.java)),
        ).thenReturn(emptySet())
        assertNull(viewModel.resolveHfrRanges(info, pair))
        Mockito.`when`(
            info.getSupportedFrameRateRanges(Mockito.any(SessionConfig::class.java)),
        ).thenThrow(IllegalStateException("no ranges"))
        assertEquals(emptyList(), viewModel.queryHfrRanges(info, viewModel.buildHfrTempConfigForTest(pair)))
    }

    private fun CameraViewModel.buildHfrTempConfigForTest(
        pair: Pair<Preview, androidx.camera.video.VideoCapture<androidx.camera.video.Recorder>>,
    ): androidx.camera.video.HighSpeedVideoSessionConfig =
        androidx.camera.video.HighSpeedVideoSessionConfig.Builder(pair.second)
            .setPreview(pair.first).setSlowMotionEnabled(true).build()

    @Test fun hfrLadderBindsHighestRangeAndFinishMarksActive() = runBlocking<Unit> {
        val viewModel = vm()
        val (preview, capture) = viewModel.buildHfrCapture(listOf(Quality.FHD))
        val session = HighSpeedPrep(
            fakeProvider(), null, emptyList(), preview, capture,
            listOf(Quality.FHD), listOf(Range(240, 240), Range(120, 120)),
        )
        assertTrue(viewModel.bindHfrLadder(session))
        assertEquals(240, viewModel.sloMoFps)
        assertNotNull(viewModel.boundCamera)
        viewModel.boundCamera = null
        viewModel.finishHighSpeedSession()
        assertEquals(true, viewModel.sloMoSupportedMutable.value)
        assertTrue(viewModel.highSpeedActiveMutable.value)
    }

    @Test fun hfrLadderFailureMarksUnsupported() {
        val viewModel = vm()
        val (preview, capture) = viewModel.buildHfrCapture(listOf(Quality.HD))
        val wide = lens("wide", LensType.WIDE, LensFacing.BACK, 0)
        val session = HighSpeedPrep(
            fakeProvider(failures = Int.MAX_VALUE), wide, listOf(wide), preview, capture,
            listOf(Quality.HD), listOf(Range(120, 120)),
        )
        assertFalse(viewModel.bindHfrLadder(session))
        assertEquals(false, viewModel.sloMoSupportedMutable.value)
        assertFalse(viewModel.tryHfrBind(session, Range(120, 120), wide))
    }

    // ---- Photo / portrait / night / pano fallback ladders ----

    @Test fun photoLadderFallsBackAfterFirstBindFailure() = runBlocking<Unit> {
        val viewModel = vm()
        val owner = ManualLifecycleOwner().apply { start() }
        val session = PhotoSessionPrep(
            fakeProvider(failures = 1), null, emptyList(),
            Preview.Builder().build(), owner, ultraHdrSupported = false,
        )
        viewModel.bindPhotoLadder(session)
        assertNotNull(viewModel.boundCamera)
    }

    @Test fun photoFallbackRecoversToPlainJpegWhenUltraFails() = runBlocking<Unit> {
        val viewModel = vm()
        val owner = ManualLifecycleOwner().apply { start() }
        val session = PhotoSessionPrep(
            fakeProvider(failures = 2), null, emptyList(),
            Preview.Builder().build(), owner, ultraHdrSupported = true,
        )
        viewModel.bindPhotoLadder(session)
        assertNotNull(viewModel.boundCamera)
        // Without Ultra HDR the same total failure is rethrown, not swallowed.
        val failing = PhotoSessionPrep(
            fakeProvider(failures = Int.MAX_VALUE), null, emptyList(),
            Preview.Builder().build(), owner, ultraHdrSupported = false,
        )
        try {
            viewModel.bindPhotoLadder(failing)
            fail("all-failing photo ladder must throw")
        } catch (e: IllegalStateException) {
            assertNotNull(e.message)
        }
        // Direct fallback helper: plain-JPEG recovery only when ultra was attempted.
        val recovered = viewModel.recoverFromFallbackFailure(
            fakeProvider(), IllegalStateException("ultra fail"), ultraHdrSupported = true,
        ) { fakeCamera() }
        assertNotNull(recovered)
    }

    @Test fun portraitLadderAllFailThrowsAndProbeDegrades() = runBlocking<Unit> {
        val viewModel = vm()
        val owner = ManualLifecycleOwner().apply { start() }
        val session = PortraitSessionPrep(
            fakeProvider(failures = Int.MAX_VALUE), null, emptyList(),
            Preview.Builder().build(), owner, ultraHdrSupported = false,
        )
        assertFalse(viewModel.tryPortraitRung(session, null, capped = true, maxRes = true, ultra = false))
        assertNotNull(portraitLastError)
        try {
            viewModel.bindPortraitLadder(session)
            fail("all-failing portrait ladder must throw")
        } catch (e: IllegalStateException) {
            assertNotNull(e.message)
        }
        val throwingProvider = Mockito.mock(ProcessCameraProvider::class.java)
        Mockito.`when`(throwingProvider.getCameraInfo(anySelector()))
            .thenThrow(IllegalArgumentException("no info"))
        assertFalse(viewModel.probePortraitUltraHdr(throwingProvider, null))
        assertFalse(viewModel.probeUltraHdr(throwingProvider, null))
    }

    @Test fun nightBindRetriesWithoutAnalysisAndPanoLadderFallsBack() = runBlocking<Unit> {
        val viewModel = vm()
        val owner = ManualLifecycleOwner().apply { start() }
        val mgr = Mockito.mock(ExtensionsManager::class.java)
        val night = NightPreviewPrep(
            fakeProvider(failures = 1), mgr, null, CameraSelector.DEFAULT_BACK_CAMERA,
            analysisSupported = true, preview = Preview.Builder().build(), owner = owner,
            capture = viewModel.buildPhotoCapture(maxRes = false, ultraHdr = false),
        )
        viewModel.bindNightPreview(night)
        assertNotNull(viewModel.boundCamera)
        assertNull(viewModel.imageAnalysis)
        val pano = PanoSessionPrep(
            fakeProvider(failures = 1), null, emptyList(), Preview.Builder().build(), owner,
        )
        viewModel.bindPanoLadder(pano)
        assertNotNull(viewModel.boundCamera)
        assertNotNull(viewModel.imageAnalysis)
    }

    // ---- Night capture entries (CameraNightCapture.kt) ----

    @Test fun nightBurstWithoutCaptureCompletesEmptyAndCustomFallsBack() {
        val viewModel = vm()
        var delivered: List<Bitmap>? = null
        viewModel.captureNightBurst(NightExposure(100_000_000L, 800)) { delivered = it }
        assertEquals(emptyList(), delivered)
        // Custom night entry: empty burst falls back to the (unbound) single capture.
        viewModel.captureNightPhotoCustom()
        assertEquals(0f, viewModel.longExposureProgressMutable.value)
        assertEquals("", viewModel.longExposureRemainingMutable.value)
        // Countdown lifecycle on its own.
        viewModel.startLongExposureCountdown(2_000_000_000L)
        assertEquals("2.0s", viewModel.longExposureRemainingMutable.value)
        viewModel.stopLongExposureCountdown()
        assertEquals(0f, viewModel.longExposureProgressMutable.value)
    }

    @Test fun singleCaptureEntriesAcrossProcessingStates() {
        for (warmth in listOf(0f, 0.5f)) {
            val viewModel = vm()
            viewModel.buildPhotoCapture(maxRes = false, ultraHdr = false)
            viewModel.setWarmth(warmth)
            if (warmth != 0f) viewModel.cameraModeMutable.value = CameraMode.PORTRAIT
            try {
                viewModel.captureSinglePhoto()
                ShadowLooper.idleMainLooper()
            } catch (e: IllegalStateException) {
                // Synchronous not-bound surface is the documented degradation.
                assertNotNull(e.message)
            }
            assertNotNull(viewModel.imageCapture)
        }
    }

    // ---- Video recording controls (CameraVideoRecording.kt) ----

    @Test fun recordingControlsWithoutActiveRecording() {
        val viewModel = vm()
        viewModel.togglePauseRecording()
        assertFalse(viewModel.recordingPausedMutable.value)
        viewModel.toggleMicMuted()
        assertTrue(viewModel.micMutedMutable.value)
        viewModel.toggleMicMuted()
        assertFalse(viewModel.micMutedMutable.value)
        // Snapshot with no ImageCapture bound is a no-op.
        viewModel.captureVideoSnapshot()
        assertFalse(viewModel.isCapturingMutable.value)
    }

    @Test fun recordingControlsDriveMockRecording() {
        val viewModel = vm()
        val recording = Mockito.mock(Recording::class.java)
        viewModel.currentRecording = recording
        viewModel.recordingPausedMutable.value = false
        viewModel.togglePauseRecording()
        assertTrue(viewModel.recordingPausedMutable.value)
        Mockito.verify(recording).pause()
        viewModel.togglePauseRecording()
        assertFalse(viewModel.recordingPausedMutable.value)
        Mockito.verify(recording).resume()
        viewModel.toggleMicMuted()
        assertTrue(viewModel.micMutedMutable.value)
        Mockito.verify(recording).mute(true)
        // A recording whose pause throws must not flip the paused flag.
        Mockito.doThrow(IllegalStateException("gone")).`when`(recording).pause()
        viewModel.togglePauseRecording()
        assertFalse(viewModel.recordingPausedMutable.value)
        viewModel.currentRecording = null
    }

    @Test fun highSpeedToggleStopsAndCancelsCountdown() {
        val viewModel = vm()
        val recording = Mockito.mock(Recording::class.java)
        viewModel.highSpeedRecording = recording
        viewModel.isRecordingMutable.value = true
        viewModel.toggleHighSpeedRecording()
        Mockito.verify(recording).stop()
        assertNull(viewModel.highSpeedRecording)
        // Countdown cancellation on both toggles.
        viewModel.isRecordingMutable.value = false
        viewModel.setTimerDuration(TimerDuration.TEN)
        viewModel.toggleRecording()
        viewModel.toggleRecording()
        assertEquals(0, viewModel.timerCountdownMutable.value)
        viewModel.toggleHighSpeedRecording()
        viewModel.toggleHighSpeedRecording()
        assertEquals(0, viewModel.timerCountdownMutable.value)
        viewModel.setTimerDuration(TimerDuration.NONE)
    }

    @Test fun panoramaStartStopThroughViewModel() {
        val viewModel = vm()
        viewModel.startPanorama()
        assertTrue(viewModel.panoramaEngine.isSweeping.value)
        assertEquals(12, viewModel.panoramaEngine.guideDots.value.size)
        viewModel.stopPanorama()
        ShadowLooper.idleMainLooper()
        assertFalse(viewModel.panoramaEngine.isSweeping.value)
        viewModel.startPhotosphere()
        assertTrue(viewModel.panoramaEngine.guideDots.value.size > 12)
        viewModel.stopPhotosphere()
        ShadowLooper.idleMainLooper()
    }

    // ---- Panorama gyro alignment (PanoramaEngine.kt) ----

    private fun gyroSensor(): Sensor {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val sensor = allocate.invoke(theUnsafe, Sensor::class.java) as Sensor
        for (name in listOf("mType", "type")) {
            try {
                val field = Sensor::class.java.getDeclaredField(name)
                field.isAccessible = true
                field.setInt(sensor, Sensor.TYPE_GYROSCOPE)
                return sensor
            } catch (_: Exception) {
            }
        }
        return sensor
    }

    private fun gyroEvent(timestampNs: Long, x: Float, y: Float, z: Float): SensorEvent {
        val ctor = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
        ctor.isAccessible = true
        val event = ctor.newInstance(3)
        event.values[0] = x
        event.values[1] = y
        event.values[2] = z
        val tsField = SensorEvent::class.java.getDeclaredField("timestamp")
        tsField.isAccessible = true
        tsField.setLong(event, timestampNs)
        val sensorField = SensorEvent::class.java.getDeclaredField("sensor")
        sensorField.isAccessible = true
        sensorField.set(event, gyroSensor())
        return event
    }

    @Test fun panoramaGyroAlignmentDirectionAndDotCapture() {
        val engine = PanoramaEngine(context)
        engine.startSweep(fullSphere = false)
        assertEquals(12, engine.guideDots.value.size)
        // First event only arms the timestamp.
        engine.onSensorChanged(gyroEvent(1_000_000_000L, 0f, 0f, 0f))
        assertEquals(0f, engine.currentAngle.value)
        // Sweep -20 degrees in 1s: direction flips negative and dots mirror.
        engine.onSensorChanged(
            gyroEvent(2_000_000_000L, 0f, Math.toRadians(20.0).toFloat(), 0f),
        )
        assertEquals(-1, engine.sweepDirection.value)
        assertTrue(engine.guideDots.value.all { it.targetAngle <= 0f })
        // Continue to the -30 degree dot at low velocity: it is marked captured.
        engine.onSensorChanged(
            gyroEvent(3_000_000_000L, 0f, Math.toRadians(10.0).toFloat(), 0f),
        )
        engine.onSensorChanged(gyroEvent(4_000_000_000L, 0f, 0f, 0f))
        val dotAt30 = engine.guideDots.value.first { it.targetAngle == -30f }
        assertEquals(GuideDotState.CAPTURED, dotAt30.state)
        assertTrue(engine.sweepAngle.value >= 29f)
        // Non-gyro events and events while stopped are ignored.
        engine.stopSweep()
        val angleBefore = engine.currentAngle.value
        engine.onSensorChanged(
            gyroEvent(5_000_000_000L, 0f, Math.toRadians(90.0).toFloat(), 0f),
        )
        assertEquals(angleBefore, engine.currentAngle.value)
        engine.reset()
        assertTrue(engine.guideDots.value.isEmpty())
    }

    @Test fun panoramaFrameHandoffRecyclesReplacedFrames() {
        val engine = PanoramaEngine(context)
        val first = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val second = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        engine.offerFrame(first, 90)
        assertEquals(90, engine.latestFrameRotation)
        engine.offerFrame(second, 180)
        assertTrue(first.isRecycled)
        val (taken, rotation) = engine.takeFrame()
        assertEquals(second, taken)
        assertEquals(180, rotation)
        assertNull(engine.latestFrame)
        engine.reset()
    }

    // ---- Lens enumeration degradation (LensSelector.kt) ----

    @Test fun lensFacingMappingsAndEnumerationDegradation() {
        assertEquals(CameraSelector.LENS_FACING_BACK, LensFacing.BACK.toSelectorInt())
        assertEquals(CameraSelector.LENS_FACING_FRONT, LensFacing.FRONT.toSelectorInt())
        assertEquals(LensFacing.BACK, CameraSelector.LENS_FACING_BACK.toLensFacing())
        assertEquals(LensFacing.FRONT, CameraSelector.LENS_FACING_FRONT.toLensFacing())
        assertNull(CameraSelector.LENS_FACING_EXTERNAL.toLensFacing())
        assertNull((-1).toLensFacing())
        val provider = Mockito.mock(ProcessCameraProvider::class.java)
        Mockito.`when`(provider.availableCameraInfos).thenReturn(emptyList())
        assertTrue(enumerateLenses(provider).isEmpty())
        // Infos that Camera2 interop cannot read are skipped, never fatal.
        val info = Mockito.mock(CameraInfo::class.java)
        Mockito.`when`(provider.availableCameraInfos).thenReturn(listOf(info))
        assertTrue(enumerateLenses(provider).isEmpty())
    }
}

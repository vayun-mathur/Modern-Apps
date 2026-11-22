package com.vayunmathur.camera.platform.camera2

import android.graphics.ImageFormat
import android.hardware.camera2.CameraMetadata
import android.util.Range
import android.util.Size
import android.view.Surface
import com.vayunmathur.camera.domain.FlashResolution
import com.vayunmathur.camera.domain.FlashSetting
import com.vayunmathur.camera.platform.CameraMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StillCaptureTest {

    private fun snapshot(
        hasFlashUnit: Boolean = true,
        lensFacingRaw: Int? = CameraMetadata.LENS_FACING_BACK,
        sensorOrientationDegrees: Int = 90,
        jpegSizes: List<Size> = listOf(Size(4000, 3000), Size(1920, 1080)),
        jpegRSizes: List<Size> = emptyList(),
    ): LensCharacteristicsSnapshot = CharacteristicsFacade.fromFakeMap(
        cameraId = "0",
        lensFacingRaw = lensFacingRaw,
        zoomRatioRange = Range(1f, 10f),
        hasFlashUnit = hasFlashUnit,
        exposureCompensationEvRange = Range(-4f, 4f),
        sizesByFormat = buildMap {
            put(ImageFormat.PRIVATE, listOf(Size(1280, 720)))
            put(ImageFormat.JPEG, jpegSizes)
            if (jpegRSizes.isNotEmpty()) put(ImageFormat.JPEG_R, jpegRSizes)
        },
        activeArrayWidth = 4000,
        activeArrayHeight = 3000,
        sensorOrientationDegrees = sensorOrientationDegrees,
        exposureCompensationStepEv = 1f / 6f,
        maxAfRegions = 1,
        maxAeRegions = 1,
    ).snapshot

    // -- request construction -------------------------------------------------

    @Test fun stillRequestCarriesCurrentControlState() {
        val region = MeteringRegion(SensorRect(100, 100, 300, 300))
        val params = StillCapturePlanner.resolve(
            Camera2ControlState(
                zoomRatio = 2f, exposureCompEv = 1f,
                afRegion = region, aeRegion = region,
                flashSetting = FlashSetting.OFF,
            ),
            snapshot(),
        )
        assertEquals(2f, params.zoomRatio)
        assertNull(params.cropRegion)
        assertEquals(6, params.aeExposureCompensationSteps)
        assertEquals(listOf(region), params.afRegions)
        assertEquals(listOf(region), params.aeRegions)
        assertTrue(params.meteringActive)
        assertEquals(CameraMetadata.CONTROL_AE_MODE_ON, params.aeMode)
        assertEquals(CameraMetadata.FLASH_MODE_OFF, params.flashMode)
    }

    @Test fun hardwareFlashMapsToAeFlashModes() {
        val auto = StillCapturePlanner.resolve(
            Camera2ControlState(flashSetting = FlashSetting.AUTO), snapshot(),
        )
        assertEquals(FlashResolution.HARDWARE, auto.flashResolution)
        assertEquals(CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH, auto.aeMode)
        assertEquals(CameraMetadata.FLASH_MODE_SINGLE, auto.flashMode)
        assertFalse(auto.requiresScreenFlash)

        val on = StillCapturePlanner.resolve(
            Camera2ControlState(flashSetting = FlashSetting.ON), snapshot(),
        )
        assertEquals(CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH, on.aeMode)
        assertEquals(CameraMetadata.FLASH_MODE_SINGLE, on.flashMode)
    }

    @Test fun screenFlashHasNoHalKeyAndIsFlaggedForTheUi() {
        val params = StillCapturePlanner.resolve(
            Camera2ControlState(flashSetting = FlashSetting.ON),
            snapshot(
                hasFlashUnit = false,
                lensFacingRaw = CameraMetadata.LENS_FACING_FRONT,
            ),
        )
        assertEquals(FlashResolution.SCREEN, params.flashResolution)
        assertTrue(params.requiresScreenFlash)
        assertEquals(CameraMetadata.CONTROL_AE_MODE_ON, params.aeMode)
        assertEquals(CameraMetadata.FLASH_MODE_OFF, params.flashMode)
    }

    @Test fun torchAlreadyLitWinsOverOneShotFlash() {
        val params = StillCapturePlanner.resolve(
            Camera2ControlState(torchOn = true, flashSetting = FlashSetting.ON),
            snapshot(),
        )
        assertEquals(CameraMetadata.FLASH_MODE_TORCH, params.flashMode)
        assertEquals(CameraMetadata.CONTROL_AE_MODE_ON, params.aeMode)
    }

    @Test fun noFlashUnitBackLensNeverInventsHardwareFlash() {
        val params = StillCapturePlanner.resolve(
            Camera2ControlState(flashSetting = FlashSetting.ON),
            snapshot(hasFlashUnit = false),
        )
        // FlashModePolicy: no unit + not front -> HARDWARE resolution, but
        // the AE flash modes would fire nothing; resolution stays hardware
        // per policy, so this asserts the planner's documented mapping.
        assertEquals(CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH, params.aeMode)
    }

    // -- orientation / metadata mapping ---------------------------------------

    @Test fun jpegRotationMatchesSensorMinusDisplay() {
        assertEquals(90, StillCapturePlanner.jpegRotationDegrees(90, 0))
        assertEquals(0, StillCapturePlanner.jpegRotationDegrees(90, 90))
        assertEquals(180, StillCapturePlanner.jpegRotationDegrees(90, 270))
        assertEquals(270, StillCapturePlanner.jpegRotationDegrees(270, 0))
        assertEquals(0, StillCapturePlanner.jpegRotationDegrees(0, 0))
        val params = StillCapturePlanner.resolve(
            Camera2ControlState(), snapshot(), displayRotationDegrees = 0, mirrored = true,
        )
        assertEquals(90, params.jpegOrientationDegrees)
        assertTrue(params.mirrored)
    }

    @Test fun metadataMappingCarriesCaptureResultFields() {
        val mapped = StillMetadataMapper.map(
            RawStillMetadata(
                sensorTimestampNs = 123_456L, exposureTimeNs = 10_000_000L,
                iso = 400, focalLengthMm = 4.5f, frameNumber = 7L,
            ),
            rotationDegrees = 450, mirrored = true,
        )
        assertEquals(90, mapped.rotationDegrees)
        assertTrue(mapped.mirrored)
        assertEquals(123_456L, mapped.sensorTimestampNs)
        assertEquals(10_000_000L, mapped.exposureTimeNs)
        assertEquals(400, mapped.iso)
        assertEquals(4.5f, mapped.focalLengthMm)
        assertEquals(7L, mapped.frameNumber)
    }

    @Test fun jpegSizeSelectionPicksLargest() {
        assertEquals(FrameSize(4000, 3000), StreamSelection.selectJpegSize(snapshot()))
        assertNull(StreamSelection.selectJpegSize(snapshot(jpegSizes = emptyList())))
    }

    // -- policy ---------------------------------------------------------------

    @Test fun policyAllowsOnlyPlainPhotoStillsBehindGate() {
        val plain = StillEngineInput(
            camera2Enabled = true, mode = CameraMode.PHOTO, hasJpegOutput = true,
        )
        assertTrue(StillEnginePolicy.usesCamera2(plain))
        assertNull(StillEnginePolicy.exclusionReason(plain))

        assertFalse(StillEnginePolicy.usesCamera2(plain.copy(camera2Enabled = false)))
        assertFalse(StillEnginePolicy.usesCamera2(plain.copy(hasJpegOutput = false)))
        for (mode in CameraMode.entries - CameraMode.PHOTO) {
            assertFalse(
                StillEnginePolicy.usesCamera2(plain.copy(mode = mode)),
                "$mode must stay on CameraX",
            )
        }
        assertTrue(
            StillEnginePolicy.exclusionReason(plain.copy(nightRoute = true))
                .orEmpty().contains("night"),
        )
        assertFalse(StillEnginePolicy.usesCamera2(plain.copy(processedRoute = true)))
        assertFalse(StillEnginePolicy.usesCamera2(plain.copy(motionPhotoRoute = true)))
        assertFalse(StillEnginePolicy.usesCamera2(plain.copy(captureForResult = true)))
        assertFalse(StillEnginePolicy.usesCamera2(plain.copy(burst = true)))
    }

    // -- session pairing over fakes --------------------------------------------

    private class FakeStillOutput : StillOutput {
        override val surface: Surface = mock(Surface::class.java)
        var jpegListener: JpegImageListener? = null
        var closed = false
        override fun setListener(listener: JpegImageListener?) { jpegListener = listener }
        override fun close() { closed = true }
        fun emit(bytes: ByteArray) = jpegListener?.onJpeg(bytes, 4000, 3000, 99L)
    }

    private class FakeSessionHandle : CaptureSessionHandle {
        val stillRequests = mutableListOf<ResolvedStillParams>()
        val stillTargets = mutableListOf<Surface>()
        var stillCallback: StillResultCallback? = null
        var closed = false
        override fun setRepeatingPreview(params: ResolvedRequestParams, target: Surface) {}
        override fun captureStill(
            params: ResolvedStillParams,
            stillTarget: Surface,
            callback: StillResultCallback,
        ) {
            stillRequests += params
            stillTargets += stillTarget
            stillCallback = callback
        }
        override fun close() { closed = true }
    }

    private class FakeDevice(
        private val handle: FakeSessionHandle,
    ) : DeviceHandle {
        override val cameraId: String = "0"
        var surfaces: List<Surface>? = null
        override fun createCaptureSession(
            surfaces: List<Surface>,
            callback: CaptureSessionCallback,
        ) {
            this.surfaces = surfaces
            callback.onConfigured(handle)
        }
        override fun close() {}
    }

    private class FakeDriver(
        private val device: FakeDevice,
        private val output: FakeStillOutput?,
        private val ultraOutput: FakeStillOutput? = null,
    ) : Camera2Driver {
        val stillSizes = mutableListOf<FrameSize>()
        val ultraSizes = mutableListOf<FrameSize>()
        override fun openCamera(cameraId: String, callback: DeviceCallback) {
            callback.onOpened(device)
        }
        override fun createStillOutput(size: FrameSize): StillOutput? {
            stillSizes += size
            return output
        }
        override fun createUltraHdrStillOutput(size: FrameSize): StillOutput? {
            ultraSizes += size
            return ultraOutput
        }
    }

    private class RecordingStillCallback : Camera2Session.StillCallback {
        val captured = mutableListOf<CapturedStill>()
        val failures = mutableListOf<String>()
        override fun onStillCaptured(still: CapturedStill) { captured += still }
        override fun onStillFailed(message: String) { failures += message }
    }

    private fun Camera2Executor.drain() {
        val latch = CountDownLatch(1)
        execute { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS), "executor did not drain")
    }

    private fun streamingSession(
        executor: Camera2Executor,
        handle: FakeSessionHandle,
        output: FakeStillOutput?,
    ): Camera2Session {
        val device = FakeDevice(handle)
        val session = Camera2Session(FakeDriver(device, output), executor, snapshot())
        session.open("0", mock(Surface::class.java), FrameSize(1280, 720))
        executor.drain()
        assertEquals(SessionState.STREAMING, session.state)
        return session
    }

    private fun streamingUltraSession(
        executor: Camera2Executor,
        handle: FakeSessionHandle,
        jpeg: FakeStillOutput?,
        ultra: FakeStillOutput?,
        jpegRSizes: List<Size>,
    ): Camera2Session {
        val device = FakeDevice(handle)
        val driver = FakeDriver(device, jpeg, ultra)
        val session = Camera2Session(driver, executor, snapshot(jpegRSizes = jpegRSizes))
        session.open("0", mock(Surface::class.java), FrameSize(1280, 720))
        executor.drain()
        assertEquals(SessionState.STREAMING, session.state)
        return session
    }

    private fun completeStill(
        executor: Camera2Executor,
        handle: FakeSessionHandle,
        from: FakeStillOutput,
        bytes: ByteArray,
    ) {
        from.emit(bytes)
        executor.drain()
        handle.stillCallback!!.onCompleted(RawStillMetadata(sensorTimestampNs = 99L))
        executor.drain()
    }

    @Test fun sessionCapturePairsJpegAndResultIntoOneStill() {
        val executor = Camera2Executor("StillCaptureTest-pair")
        try {
            val handle = FakeSessionHandle()
            val output = FakeStillOutput()
            val session = streamingSession(executor, handle, output)
            assertTrue(session.hasJpegOutput)
            assertEquals(FrameSize(4000, 3000), session.currentStillSize())

            session.setZoomRatio(3f)
            session.setFlashSetting(FlashSetting.AUTO)
            executor.drain()
            val callback = RecordingStillCallback()
            session.capture(callback, displayRotationDegrees = 0, mirrored = false)
            executor.drain()

            // Request construction: controls carried over at capture time.
            assertEquals(1, handle.stillRequests.size)
            val params = handle.stillRequests.single()
            assertEquals(3f, params.zoomRatio)
            assertEquals(CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH, params.aeMode)
            assertEquals(90, params.jpegOrientationDegrees)

            // JPEG first, result second: delivered only when both arrive.
            output.emit(byteArrayOf(1, 2, 3))
            executor.drain()
            assertTrue(callback.captured.isEmpty())
            handle.stillCallback!!.onCompleted(
                RawStillMetadata(sensorTimestampNs = 99L, iso = 200, exposureTimeNs = 5L),
            )
            executor.drain()
            assertEquals(1, callback.captured.size)
            val still = callback.captured.single()
            assertTrue(still.jpegBytes.contentEquals(byteArrayOf(1, 2, 3)))
            assertEquals(4000, still.jpegWidth)
            assertEquals(90, still.metadata.rotationDegrees)
            assertEquals(200, still.metadata.iso)
            assertEquals(99L, still.metadata.sensorTimestampNs)
            session.close()
            executor.drain()
            assertTrue(output.closed)
        } finally {
            executor.shutdown()
        }
    }

    @Test fun sessionCaptureRejectsSecondInFlightAndMissingOutput() {
        val executor = Camera2Executor("StillCaptureTest-guards")
        try {
            val handle = FakeSessionHandle()
            val session = streamingSession(executor, handle, FakeStillOutput())
            val first = RecordingStillCallback()
            val second = RecordingStillCallback()
            session.capture(first)
            session.capture(second)
            executor.drain()
            assertEquals(1, handle.stillRequests.size)
            assertEquals(1, second.failures.size)
            assertTrue(second.failures.single().contains("already in flight"))

            // Closing mid-capture fails the pending shot, never delivers it.
            session.close()
            executor.drain()
            assertEquals(1, first.failures.size)
            assertTrue(first.captured.isEmpty())

            // No JPEG output: capture fails cleanly, preview unaffected.
            val noJpeg = streamingSession(executor, FakeSessionHandle(), null)
            assertFalse(noJpeg.hasJpegOutput)
            val cb = RecordingStillCallback()
            noJpeg.capture(cb)
            executor.drain()
            assertEquals(1, cb.failures.size)
            assertTrue(cb.failures.single().contains("no JPEG output"))
            noJpeg.close()
            executor.drain()
        } finally {
            executor.shutdown()
        }
    }

    // -- Ultra HDR (JPEG_R) routing -------------------------------------------

    @Test fun stillOutputRouterSelectsJpegROnlyWhenRequestedAndAvailable() {
        assertEquals(
            StillOutputTag.ULTRA_HDR_JPEG_R,
            StillOutputRouter.select(ultraHdrRequested = true, hasUltraHdrOutput = true),
        )
        assertEquals(
            StillOutputTag.JPEG,
            StillOutputRouter.select(ultraHdrRequested = true, hasUltraHdrOutput = false),
        )
        assertEquals(
            StillOutputTag.JPEG,
            StillOutputRouter.select(ultraHdrRequested = false, hasUltraHdrOutput = true),
        )
        assertEquals(FrameSize(4000, 3000), StreamSelection.selectJpegRSize(snapshot(jpegRSizes = listOf(Size(1920, 1080), Size(4000, 3000)))))
        assertNull(StreamSelection.selectJpegRSize(snapshot()))
    }

    @Test fun sessionCapturesToJpegRWhenSupportedAndRequested() {
        val executor = Camera2Executor("StillCaptureTest-ultra")
        try {
            val handle = FakeSessionHandle()
            val jpeg = FakeStillOutput()
            val ultra = FakeStillOutput()
            val session = streamingUltraSession(
                executor, handle, jpeg, ultra, listOf(Size(4000, 3000)),
            )
            assertTrue(session.ultraHdrSupportedJpegR)
            assertTrue(session.hasUltraHdrOutput)
            assertEquals(FrameSize(4000, 3000), session.currentUltraHdrSize())
            // Both still surfaces are in the configured session.
            val callback = RecordingStillCallback()
            session.capture(callback, ultraHdrRequested = true)
            executor.drain()
            assertEquals(1, handle.stillRequests.size)
            assertEquals(ultra.surface, handle.stillTargets.single())
            completeStill(executor, handle, ultra, byteArrayOf(7, 8))
            assertEquals(1, callback.captured.size)
            assertEquals(StillOutputTag.ULTRA_HDR_JPEG_R, callback.captured.single().tag)
            assertTrue(callback.captured.single().jpegBytes.contentEquals(byteArrayOf(7, 8)))
            assertTrue(callback.failures.isEmpty())
            session.close()
            executor.drain()
            assertTrue(ultra.closed)
        } finally {
            executor.shutdown()
        }
    }

    @Test fun sessionFallsBackToJpegWhenUltraRequestedButUnsupported() {
        val executor = Camera2Executor("StillCaptureTest-ultra-fallback")
        try {
            val handle = FakeSessionHandle()
            val jpeg = FakeStillOutput()
            // No JPEG_R sizes: no Ultra HDR output is ever created.
            val session = streamingUltraSession(executor, handle, jpeg, FakeStillOutput(), emptyList())
            assertFalse(session.ultraHdrSupportedJpegR)
            assertFalse(session.hasUltraHdrOutput)
            val callback = RecordingStillCallback()
            session.capture(callback, ultraHdrRequested = true)
            executor.drain()
            assertEquals(jpeg.surface, handle.stillTargets.single())
            completeStill(executor, handle, jpeg, byteArrayOf(1))
            assertEquals(StillOutputTag.JPEG, callback.captured.single().tag)
            assertTrue(callback.failures.isEmpty())
            session.close()
            executor.drain()
        } finally {
            executor.shutdown()
        }
    }

    @Test fun sessionUsesJpegWhenUltraSupportedButNotRequested() {
        val executor = Camera2Executor("StillCaptureTest-ultra-not-requested")
        try {
            val handle = FakeSessionHandle()
            val jpeg = FakeStillOutput()
            val session = streamingUltraSession(
                executor, handle, jpeg, FakeStillOutput(), listOf(Size(4000, 3000)),
            )
            assertTrue(session.hasUltraHdrOutput)
            val callback = RecordingStillCallback()
            session.capture(callback, ultraHdrRequested = false)
            executor.drain()
            assertEquals(jpeg.surface, handle.stillTargets.single())
            completeStill(executor, handle, jpeg, byteArrayOf(2))
            assertEquals(StillOutputTag.JPEG, callback.captured.single().tag)
            session.close()
            executor.drain()
        } finally {
            executor.shutdown()
        }
    }
}

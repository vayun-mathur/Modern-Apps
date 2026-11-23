package com.vayunmathur.camera.platform.camera2

import android.graphics.ImageFormat
import android.hardware.camera2.CameraMetadata
import android.util.Range
import android.util.Size
import android.view.Surface
import com.vayunmathur.camera.platform.CameraMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReprocessRingSessionTest {

    // -- fakes ----------------------------------------------------------------

    private class FakeRingFrame(
        ts: Long,
        exposureNs: Long? = 10L,
        private val jpeg: ByteArray? = byteArrayOf(7, 7, 7),
    ) : RingHeldFrame {
        override val meta = RingFrameMeta(
            sensorTimestampNs = ts, exposureTimeNs = exposureNs,
            width = 1920, height = 1080,
        )
        val closes = AtomicInteger(0)
        val encodes = AtomicInteger(0)
        override fun encodeJpeg(quality: Int): ByteArray? {
            encodes.incrementAndGet()
            return jpeg
        }
        override fun close() { closes.incrementAndGet() }
    }

    private class FakeRingOutput : RingOutput {
        override val surface: Surface = mock(Surface::class.java)
        var ringListener: RingFrameListener? = null
        var closed = false
        override fun setListener(listener: RingFrameListener?) { ringListener = listener }
        override fun close() { closed = true }
        fun emit(frame: RingHeldFrame) = ringListener?.onRingFrame(frame)
    }

    private class FakeStillOutput : StillOutput {
        override val surface: Surface = mock(Surface::class.java)
        var jpegListener: JpegImageListener? = null
        var closed = false
        override fun setListener(listener: JpegImageListener?) { jpegListener = listener }
        override fun close() { closed = true }
        fun emit(bytes: ByteArray) = jpegListener?.onJpeg(bytes, 4000, 3000, 99L)
    }

    private class FakeHandle(
        var reprocessResult: Boolean = false,
    ) : CaptureSessionHandle {
        val stillRequests = mutableListOf<ResolvedStillParams>()
        var stillCallback: StillResultCallback? = null
        val reprocessFrames = mutableListOf<RingHeldFrame>()
        var reprocessCallback: StillResultCallback? = null
        var streamingTargets: List<Surface> = emptyList()
        var previewCallback: PreviewResultCallback? = null
        var closed = false
        override fun setRepeatingPreview(params: ResolvedRequestParams, target: Surface) {}
        override fun setRepeatingStreaming(
            params: ResolvedRequestParams, targets: List<Surface>, callback: PreviewResultCallback?,
        ) {
            streamingTargets = targets
            previewCallback = callback
        }
        override fun captureStill(
            params: ResolvedStillParams, stillTarget: Surface, callback: StillResultCallback,
        ) {
            stillRequests += params
            stillCallback = callback
        }
        override fun captureReprocess(
            params: ResolvedStillParams, stillTarget: Surface,
            frame: RingHeldFrame, callback: StillResultCallback,
        ): Boolean {
            reprocessFrames += frame
            reprocessCallback = callback
            return reprocessResult
        }
        override fun close() { closed = true }
    }

    private class FakeDevice(private val handle: FakeHandle) : DeviceHandle {
        override val cameraId: String = "0"
        var surfaces: List<Surface>? = null
        var reprocessInput: ReprocessInputConfig? = null
        override fun createCaptureSession(surfaces: List<Surface>, callback: CaptureSessionCallback) {
            this.surfaces = surfaces
            callback.onConfigured(handle)
        }
        override fun createReprocessableCaptureSession(
            input: ReprocessInputConfig, surfaces: List<Surface>, callback: CaptureSessionCallback,
        ) {
            this.surfaces = surfaces
            reprocessInput = input
            callback.onConfigured(handle)
        }
        override fun close() {}
    }

    private class FakeDriver(
        private val device: FakeDevice,
        private val still: FakeStillOutput?,
        private val ring: FakeRingOutput?,
    ) : Camera2Driver {
        override fun openCamera(cameraId: String, callback: DeviceCallback) =
            callback.onOpened(device)
        override fun createStillOutput(size: FrameSize): StillOutput? = still
        override fun createRingOutput(size: FrameSize): RingOutput? = ring
    }

    private class RecordingCallback : Camera2Session.StillCallback {
        val captured = mutableListOf<CapturedStill>()
        val failures = mutableListOf<String>()
        override fun onStillCaptured(still: CapturedStill) { captured += still }
        override fun onStillFailed(message: String) { failures += message }
    }

    private fun snapshot(reprocess: Boolean): LensCharacteristicsSnapshot =
        CharacteristicsFacade.fromFakeMap(
            cameraId = "0",
            lensFacingRaw = CameraMetadata.LENS_FACING_BACK,
            zoomRatioRange = Range(1f, 10f),
            hasFlashUnit = true,
            exposureCompensationEvRange = Range(-4f, 4f),
            sizesByFormat = mapOf(
                ImageFormat.PRIVATE to listOf(Size(1280, 720)),
                ImageFormat.JPEG to listOf(Size(4000, 3000)),
                ImageFormat.YUV_420_888 to listOf(Size(1920, 1080)),
            ),
            availableCapabilities = if (reprocess) {
                setOf(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING)
            } else {
                emptySet()
            },
            activeArrayWidth = 4000,
            activeArrayHeight = 3000,
            sensorOrientationDegrees = 90,
            exposureCompensationStepEv = 1f / 6f,
            maxAfRegions = 1,
            maxAeRegions = 1,
            reprocessInputSizesByFormat = if (reprocess) {
                mapOf(ImageFormat.YUV_420_888 to listOf(Size(1920, 1080)))
            } else {
                emptyMap()
            },
        ).snapshot

    private fun Camera2Executor.drain() {
        val latch = CountDownLatch(1)
        execute { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS), "executor did not drain")
    }

    private fun streaming(
        executor: Camera2Executor,
        handle: FakeHandle,
        reprocess: Boolean,
        ring: FakeRingOutput? = FakeRingOutput(),
    ): Triple<Camera2Session, FakeStillOutput, FakeDevice> {
        val still = FakeStillOutput()
        val device = FakeDevice(handle)
        val session = Camera2Session(
            FakeDriver(device, still, ring), executor, snapshot(reprocess),
            ringConfig = ReprocessRingConfig(enabled = true),
        )
        session.open("0", mock(Surface::class.java), FrameSize(1280, 720))
        executor.drain()
        assertEquals(SessionState.STREAMING, session.state)
        return Triple(session, still, device)
    }

    // -- policy ---------------------------------------------------------------

    @Test fun ringPolicyNeedsReprocessRingOutputAndPlainPhoto() {
        val plain = StillEngineInput(
            mode = CameraMode.PHOTO, hasJpegOutput = true,
            ringEnabled = true, reprocessSupported = true, hasRingOutput = true,
        )
        assertEquals(StillEngine.CAMERA2_RING, StillEnginePolicy.select(plain))
        assertTrue(StillEnginePolicy.usesRing(plain))
        assertTrue(StillEnginePolicy.usesReprocess(plain))
        assertTrue(StillEnginePolicy.usesCamera2(plain))
        // Each requirement removed drops back to the plain direct path.
        assertEquals(StillEngine.CAMERA2, StillEnginePolicy.select(plain.copy(ringEnabled = false)))
        assertEquals(StillEngine.CAMERA2, StillEnginePolicy.select(plain.copy(reprocessSupported = false)))
        assertEquals(StillEngine.CAMERA2, StillEnginePolicy.select(plain.copy(hasRingOutput = false)))
        // Non-plain shots take their dedicated route even with every ring flag on.
        assertEquals(StillEngine.CAMERA2_ROUTED, StillEnginePolicy.select(plain.copy(nightRoute = true)))
        assertEquals(StillEngine.CAMERA2_ROUTED, StillEnginePolicy.select(plain.copy(burst = true)))
        assertEquals(StillEngine.CAMERA2_ROUTED, StillEnginePolicy.select(plain.copy(mode = CameraMode.PORTRAIT)))
        assertTrue(StillEnginePolicy.ringExclusionReason(plain.copy(nightRoute = true)).orEmpty().contains("night"))
    }

    @Test fun facadeReportsYuvReprocessOnlyForYuvCapability() {
        assertTrue(snapshot(true).supportsYuvRingReprocess)
        assertEquals(ImageFormat.YUV_420_888, snapshot(true).reprocessInputFormat)
        assertFalse(snapshot(false).supportsYuvRingReprocess)
        assertNull(snapshot(false).reprocessInputFormat)
        val privateOnly = CharacteristicsFacade.fromFakeMap(
            cameraId = "0",
            availableCapabilities = setOf(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING,
            ),
        ).snapshot
        assertEquals(ImageFormat.PRIVATE, privateOnly.reprocessInputFormat)
        assertFalse(privateOnly.supportsYuvRingReprocess) // YUV ring cannot queue to PRIVATE
    }

    // -- session --------------------------------------------------------------

    @Test fun ringConfiguresReprocessableSessionWithRingTarget() {
        val executor = Camera2Executor("RingSessionTest-config")
        try {
            val handle = FakeHandle()
            val ring = FakeRingOutput()
            val (session, _, device) = streaming(executor, handle, reprocess = true, ring = ring)
            assertTrue(session.hasRingOutput)
            assertTrue(session.reprocessSupported)
            assertEquals(FrameSize(1920, 1080), session.currentRingSize())
            assertEquals(
                ReprocessInputConfig(1920, 1080, ImageFormat.YUV_420_888), device.reprocessInput,
            )
            assertEquals(3, device.surfaces!!.size) // preview + JPEG + ring
            assertEquals(2, handle.streamingTargets.size) // preview + ring
            session.close(); executor.drain()
            assertTrue(ring.closed)
        } finally { executor.shutdown() }
    }

    @Test fun directFallbackEncodesAlreadyExposedFrameWithoutStillRequest() {
        val executor = Camera2Executor("RingSessionTest-direct")
        try {
            val handle = FakeHandle()
            val ring = FakeRingOutput()
            val (session, _, _) = streaming(executor, handle, reprocess = false, ring = ring)
            val older = FakeRingFrame(100)
            val nearer = FakeRingFrame(200)
            ring.emit(older); ring.emit(nearer); executor.drain()
            assertEquals(2, session.ringFrameCount)

            val callback = RecordingCallback()
            session.captureFromRing(callback, shutterTimestampNs = 215)
            executor.drain()
            assertEquals(1, callback.captured.size)
            assertTrue(callback.captured.single().jpegBytes.contentEquals(byteArrayOf(7, 7, 7)))
            assertEquals(200L, callback.captured.single().metadata.sensorTimestampNs)
            assertEquals(0, handle.stillRequests.size) // no fresh exposure was requested
            assertEquals(1, nearer.encodes.get())
            assertEquals(1, nearer.closes.get())
            assertEquals(0, older.encodes.get())
            session.close(); executor.drain()
        } finally { executor.shutdown() }
    }

    @Test fun reprocessPathQueuesRingFrameAndPairsJpeg() {
        val executor = Camera2Executor("RingSessionTest-reprocess")
        try {
            val handle = FakeHandle(reprocessResult = true)
            val ring = FakeRingOutput()
            val (session, still, _) = streaming(executor, handle, reprocess = true, ring = ring)
            val frame = FakeRingFrame(200)
            ring.emit(frame); executor.drain()
            val callback = RecordingCallback()
            session.captureFromRing(callback, shutterTimestampNs = 210)
            executor.drain()
            assertEquals(1, handle.reprocessFrames.size)
            assertEquals(0, frame.encodes.get()) // HAL reprocess, not direct encode
            // JPEG arrives through the still output, result through the callback.
            still.emit(byteArrayOf(1, 2))
            handle.reprocessCallback!!.onCompleted(RawStillMetadata(sensorTimestampNs = 200, iso = 100))
            executor.drain()
            assertEquals(1, callback.captured.size)
            assertEquals(100, callback.captured.single().metadata.iso)
            session.close(); executor.drain()
        } finally { executor.shutdown() }
    }

    @Test fun reprocessRuntimeRefusalFallsBackToDirectEncode() {
        val executor = Camera2Executor("RingSessionTest-refusal")
        try {
            val handle = FakeHandle(reprocessResult = false)
            val ring = FakeRingOutput()
            val (session, _, _) = streaming(executor, handle, reprocess = true, ring = ring)
            ring.emit(FakeRingFrame(200)); executor.drain()
            val callback = RecordingCallback()
            session.captureFromRing(callback, shutterTimestampNs = 210)
            executor.drain()
            assertEquals(1, handle.reprocessFrames.size)
            assertEquals(1, callback.captured.size) // same frame, direct-encoded
            assertEquals(0, handle.stillRequests.size)
            session.close(); executor.drain()
        } finally { executor.shutdown() }
    }

    @Test fun ringMissFallsBackToRegularStillCapture() {
        val executor = Camera2Executor("RingSessionTest-miss")
        try {
            val handle = FakeHandle()
            val ring = FakeRingOutput()
            val (session, _, _) = streaming(executor, handle, reprocess = true, ring = ring)
            // Only a still-exposing frame: ends 350, shutter at 260.
            ring.emit(FakeRingFrame(250, exposureNs = 100)); executor.drain()
            val callback = RecordingCallback()
            session.captureFromRing(callback, shutterTimestampNs = 260)
            executor.drain()
            assertEquals(1, handle.stillRequests.size) // fresh still, shutter not lost
            assertTrue(callback.captured.isEmpty())
            session.close(); executor.drain()
            assertEquals(1, callback.failures.size) // close fails the pending fresh still
        } finally { executor.shutdown() }
    }

    @Test fun previewResultsPatchRingFrameExposure() {
        val executor = Camera2Executor("RingSessionTest-meta")
        try {
            val handle = FakeHandle()
            val ring = FakeRingOutput()
            val (session, _, _) = streaming(executor, handle, reprocess = false, ring = ring)
            ring.emit(FakeRingFrame(200, exposureNs = null)); executor.drain()
            // Preview result for the same sensor timestamp arrives after.
            handle.previewCallback!!.onPreviewResult(
                RawStillMetadata(sensorTimestampNs = 200, exposureTimeNs = 10, iso = 320),
            )
            executor.drain()
            val callback = RecordingCallback()
            session.captureFromRing(callback, shutterTimestampNs = 215)
            executor.drain()
            assertEquals(1, callback.captured.size)
            assertEquals(320, callback.captured.single().metadata.iso)
            assertEquals(10L, callback.captured.single().metadata.exposureTimeNs)
            session.close(); executor.drain()
        } finally { executor.shutdown() }
    }
}

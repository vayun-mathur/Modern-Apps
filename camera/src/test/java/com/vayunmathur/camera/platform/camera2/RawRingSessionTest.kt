package com.vayunmathur.camera.platform.camera2

import android.graphics.ImageFormat
import android.hardware.camera2.CameraMetadata
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RawRingSessionTest {

    private class FakeRawFrame(ts: Long) : RawHeldFrame {
        override val meta = RingFrameMeta(
            sensorTimestampNs = ts, exposureTimeNs = 10,
            width = 1920, height = 1440, format = RingFormat.RAW_SENSOR,
        )
        override val format: RingFormat = RingFormat.RAW_SENSOR
        val closes = AtomicInteger(0)
        override fun copyRawBytes(): ByteArray = ByteArray(4)
        override fun close() { closes.incrementAndGet() }
    }

    private class FakeRawOutput : RawRingOutput {
        override val surface: Surface = mock(Surface::class.java)
        var ringListener: RawRingFrameListener? = null
        var closed = false
        override fun setListener(listener: RawRingFrameListener?) { ringListener = listener }
        override fun close() { closed = true }
        fun emit(frame: RawHeldFrame) = ringListener?.onRawRingFrame(frame)
    }

    private class FakeHandle : CaptureSessionHandle {
        var streamingTargets: List<Surface> = emptyList()
        override fun setRepeatingPreview(params: ResolvedRequestParams, target: Surface) {}
        override fun setRepeatingStreaming(
            params: ResolvedRequestParams, targets: List<Surface>, callback: PreviewResultCallback?,
        ) { streamingTargets = targets }
        override fun captureStill(
            params: ResolvedStillParams, stillTarget: Surface, callback: StillResultCallback,
        ) {}
        override fun close() {}
    }

    private class FakeDevice(private val handle: FakeHandle) : DeviceHandle {
        override val cameraId: String = "0"
        var surfaces: List<Surface>? = null
        override fun createCaptureSession(surfaces: List<Surface>, callback: CaptureSessionCallback) {
            this.surfaces = surfaces
            callback.onConfigured(handle)
        }
        override fun close() {}
    }

    private class FakeDriver(
        private val device: FakeDevice,
        private val raw: FakeRawOutput?,
    ) : Camera2Driver {
        override fun openCamera(cameraId: String, callback: DeviceCallback) =
            callback.onOpened(device)
        override fun createStillOutput(size: FrameSize): StillOutput? = null
        override fun createRingOutput(size: FrameSize): RingOutput? = null
        override fun createRawRingOutput(size: FrameSize, format: RingFormat): RawRingOutput? = raw
    }

    private fun snapshot() = CharacteristicsFacade.fromFakeMap(
        cameraId = "0",
        lensFacingRaw = CameraMetadata.LENS_FACING_BACK,
        zoomRatioRange = Range(1f, 10f),
        sizesByFormat = mapOf(
            ImageFormat.PRIVATE to listOf(Size(1280, 720)),
            ImageFormat.RAW_SENSOR to listOf(Size(1920, 1440)),
        ),
        availableCapabilities = setOf(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW),
        activeArrayWidth = 1920,
        activeArrayHeight = 1440,
        sensorOrientationDegrees = 90,
        exposureCompensationStepEv = 1f / 6f,
    ).snapshot

    private fun Camera2Executor.drain() {
        val latch = CountDownLatch(1)
        execute { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
    }

    @Test fun dualRingConfiguresRawOutputAndServesBurst() {
        val executor = Camera2Executor("RawRingSessionTest")
        try {
            val handle = FakeHandle()
            val raw = FakeRawOutput()
            val device = FakeDevice(handle)
            val session = Camera2Session(
                FakeDriver(device, raw), executor, snapshot(),
                rawRingConfig = RawRingConfig(enabled = true),
            )
            session.open("0", mock(Surface::class.java), FrameSize(1280, 720))
            executor.drain()
            assertEquals(SessionState.STREAMING, session.state)
            assertTrue(session.hasRawRingOutput)
            assertTrue(session.rawSupported)
            assertEquals(FrameSize(1920, 1440), session.currentRawRingSize())
            assertEquals(RingFormat.RAW_SENSOR, session.currentRawRingFormat())

            raw.emit(FakeRawFrame(100)); raw.emit(FakeRawFrame(200)); raw.emit(FakeRawFrame(300))
            executor.drain()
            assertEquals(3, session.rawRingFrameCount)

            var burst: List<RawHeldFrame>? = null
            var failure: String? = null
            session.captureRawBurst(2, object : Camera2Session.RawBurstCallback {
                override fun onRawBurst(frames: List<RawHeldFrame>, metas: List<RingFrameMeta>) {
                    burst = frames
                }
                override fun onRawBurstFailed(message: String) { failure = message }
            }, shutterTimestampNs = 320)
            executor.drain()
            assertEquals(null, failure)
            assertEquals(listOf(200L, 300L), burst!!.map { it.meta.sensorTimestampNs })
            assertEquals(1, session.rawRingFrameCount) // oldest remains held
            burst!!.forEach { it.close() }
            session.close(); executor.drain()
            assertTrue(raw.closed)
        } finally { executor.shutdown() }
    }

    @Test fun rawBurstFailsWithFewerThanTwoCompleteFrames() {
        val executor = Camera2Executor("RawRingSessionTest-short")
        try {
            val raw = FakeRawOutput()
            val session = Camera2Session(
                FakeDriver(FakeDevice(FakeHandle()), raw), executor, snapshot(),
                rawRingConfig = RawRingConfig(enabled = true),
            )
            session.open("0", mock(Surface::class.java), FrameSize(1280, 720))
            executor.drain()
            raw.emit(FakeRawFrame(100)); executor.drain()
            var failure: String? = null
            session.captureRawBurst(6, object : Camera2Session.RawBurstCallback {
                override fun onRawBurst(frames: List<RawHeldFrame>, metas: List<RingFrameMeta>) {}
                override fun onRawBurstFailed(message: String) { failure = message }
            }, shutterTimestampNs = 200)
            executor.drain()
            assertTrue(failure.orEmpty().contains("need 2+"), "got: $failure")
            session.close(); executor.drain()
        } finally { executor.shutdown() }
    }
}

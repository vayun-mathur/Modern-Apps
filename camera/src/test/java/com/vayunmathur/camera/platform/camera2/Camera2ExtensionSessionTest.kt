package com.vayunmathur.camera.platform.camera2

import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraMetadata
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase-6 extension-session tests over driver fakes: the extension-session
 * request construction (EXTENSION_NIGHT, preview+still surfaces, never a
 * regular session), JPEG delivery into the Phase-4a [CapturedStill] shape,
 * up-front probe rejection, configure failure, the seam's fail-don't-
 * substitute default, and stale-generation drops.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Camera2ExtensionSessionTest {

    private val night = CameraExtensionCharacteristics.EXTENSION_NIGHT

    private class FakeExtensionHandle : CaptureSessionHandle {
        val repeating = mutableListOf<ResolvedRequestParams>()
        val stills = mutableListOf<Pair<ResolvedStillParams, Surface>>()
        var closed = false
        override fun setRepeatingPreview(params: ResolvedRequestParams, target: Surface) {
            repeating += params
        }
        override fun captureStill(
            params: ResolvedStillParams,
            stillTarget: Surface,
            callback: StillResultCallback,
        ) {
            stills += params to stillTarget
            callback.onCompleted(
                RawStillMetadata(sensorTimestampNs = 99L, iso = 3200, exposureTimeNs = 33_000_000L),
            )
        }
        override fun close() { closed = true }
    }

    private class FakeStillOutput(
        override val surface: Surface = mock(Surface::class.java),
    ) : StillOutput {
        private var listener: JpegImageListener? = null
        var closed = false
        override fun setListener(listener: JpegImageListener?) { this.listener = listener }
        fun emitJpeg(bytes: ByteArray, width: Int, height: Int) =
            listener?.onJpeg(bytes, width, height, 123L)
        override fun close() { closed = true }
    }

    private open class FakeDeviceHandle(
        override val cameraId: String = "0",
        val sessionHandle: FakeExtensionHandle = FakeExtensionHandle(),
        private val failConfigure: Boolean = false,
    ) : DeviceHandle {
        var regularSessionRequested = false
        var extensionMode: Int? = null
        var extensionSurfaces: List<Surface>? = null
        var closed = false
        override fun createCaptureSession(
            surfaces: List<Surface>,
            callback: CaptureSessionCallback,
        ) {
            regularSessionRequested = true
            callback.onConfigured(sessionHandle)
        }
        override fun createExtensionCaptureSession(
            extensionMode: Int,
            surfaces: List<Surface>,
            callback: CaptureSessionCallback,
        ) {
            this.extensionMode = extensionMode
            extensionSurfaces = surfaces
            if (failConfigure) callback.onConfigureFailed() else callback.onConfigured(sessionHandle)
        }
        override fun close() { closed = true }
    }

    /** Device that does not override the extension seam: must fail, not substitute. */
    private class RegularOnlyDevice(override val cameraId: String = "0") : DeviceHandle {
        var regularSessionRequested = false
        override fun createCaptureSession(
            surfaces: List<Surface>,
            callback: CaptureSessionCallback,
        ) {
            regularSessionRequested = true
            callback.onConfigured(FakeExtensionHandle())
        }
        override fun close() {}
    }

    private class FakeDriver(
        val device: DeviceHandle = FakeDeviceHandle(),
        val stillOutput: FakeStillOutput = FakeStillOutput(),
    ) : Camera2Driver {
        var openedCameraId: String? = null
        var stillSizeRequested: FrameSize? = null
        override fun openCamera(cameraId: String, callback: DeviceCallback) {
            openedCameraId = cameraId
            callback.onOpened(device)
        }
        override fun createStillOutput(size: FrameSize): StillOutput? {
            stillSizeRequested = size
            return stillOutput
        }
    }

    private class RecordingListener : Camera2ExtensionSession.Listener {
        val errors = mutableListOf<String>()
        val streaming = mutableListOf<ExtensionProbeResult>()
        override fun onError(message: String) { errors += message }
        override fun onStreaming(probe: ExtensionProbeResult) { streaming += probe }
    }

    private fun snapshot(
        modes: List<Int> = listOf(night),
        support: Map<Int, ExtensionModeSupport> = mapOf(
            night to ExtensionModeSupport(
                mode = night,
                jpegSizes = listOf(Size(4032, 3024), Size(1920, 1080)),
                previewSizes = listOf(Size(1920, 1080), Size(1280, 720)),
                estimatedCaptureLatencyRangeMillisByJpegSize = mapOf(
                    Size(4032, 3024) to Range(800L, 1500L),
                ),
            ),
        ),
    ): LensCharacteristicsSnapshot = CharacteristicsFacade.fromFakeMap(
        cameraId = "0",
        lensFacingRaw = CameraMetadata.LENS_FACING_BACK,
        hasFlashUnit = true,
        availableExtensionModes = modes,
        extensionSupport = support,
        activeArrayWidth = 4032,
        activeArrayHeight = 3024,
        sensorOrientationDegrees = 90,
    ).snapshot

    private fun Camera2Executor.drain() {
        val latch = CountDownLatch(1)
        execute { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS), "executor did not drain")
    }

    @Test fun openConfiguresExtensionSessionAndCaptureDeliversJpegStill() {
        val executor = Camera2Executor("ExtensionSessionTest-seq")
        try {
            val device = FakeDeviceHandle()
            val driver = FakeDriver(device)
            val listener = RecordingListener()
            val session = Camera2ExtensionSession(driver, executor, snapshot(), listener = listener)
            val preview = mock(Surface::class.java)

            session.open("0", preview, FrameSize(1280, 720))
            executor.drain()

            assertEquals("0", driver.openedCameraId)
            assertEquals(SessionState.STREAMING, session.state)
            // Extension seam with NIGHT + [preview, still]; regular never used.
            assertTrue(!device.regularSessionRequested)
            assertEquals(night, device.extensionMode)
            assertEquals(listOf(preview, driver.stillOutput.surface), device.extensionSurfaces)
            assertEquals(FrameSize(4032, 3024), driver.stillSizeRequested)
            assertEquals(FrameSize(4032, 3024), session.currentStillSize())
            assertTrue(session.hasJpegOutput)
            assertEquals(Range(800L, 1500L), listener.streaming.single().estimatedCaptureLatencyRangeMillis)
            assertEquals(1, device.sessionHandle.repeating.size)

            // Capture: request construction carries still fields; JPEG + metadata pair up.
            val captured = mutableListOf<CapturedStill>()
            val failed = mutableListOf<String>()
            session.capture(object : Camera2ExtensionSession.StillCallback {
                override fun onStillCaptured(still: CapturedStill) { captured += still }
                override fun onStillFailed(message: String) { failed += message }
            })
            executor.drain()
            assertEquals(1, device.sessionHandle.stills.size)
            assertEquals(driver.stillOutput.surface, device.sessionHandle.stills.single().second)
            // JPEG arrives after the result metadata (either order must pair).
            driver.stillOutput.emitJpeg(byteArrayOf(1, 2, 3), 4032, 3024)
            executor.drain()
            assertTrue(failed.isEmpty())
            val still = captured.single()
            assertTrue(still.jpegBytes.contentEquals(byteArrayOf(1, 2, 3)))
            assertEquals(4032, still.jpegWidth)
            assertEquals(3200, still.metadata.iso)
            assertEquals(90, still.metadata.rotationDegrees)

            session.close()
            executor.drain()
            assertEquals(SessionState.CLOSED, session.state)
            assertTrue(device.sessionHandle.closed)
            assertTrue(device.closed)
        } finally {
            executor.shutdown()
        }
    }

    @Test fun unusableProbeFailsBeforeOpeningDevice() {
        val executor = Camera2Executor("ExtensionSessionTest-noprobe")
        try {
            val driver = FakeDriver()
            val listener = RecordingListener()
            val session = Camera2ExtensionSession(
                driver, executor, snapshot(modes = emptyList(), support = emptyMap()),
                listener = listener,
            )
            session.open("0", mock(Surface::class.java), FrameSize(1280, 720))
            executor.drain()
            assertEquals(null, driver.openedCameraId)
            assertEquals(SessionState.CLOSED, session.state)
            assertTrue(listener.errors.single().contains("unusable"))
        } finally {
            executor.shutdown()
        }
    }

    @Test fun configureFailureReportsErrorAndNeverStreams() {
        val executor = Camera2Executor("ExtensionSessionTest-cfgfail")
        try {
            val driver = FakeDriver(FakeDeviceHandle(failConfigure = true))
            val listener = RecordingListener()
            val session = Camera2ExtensionSession(driver, executor, snapshot(), listener = listener)
            session.open("0", mock(Surface::class.java), FrameSize(1280, 720))
            executor.drain()
            assertEquals(SessionState.OPEN, session.state)
            assertTrue(listener.errors.any { it.contains("configure failed") })
            assertTrue(listener.streaming.isEmpty())
        } finally {
            executor.shutdown()
        }
    }

    @Test fun defaultExtensionSeamFailsRatherThanSubstitutingRegularSession() {
        val executor = Camera2Executor("ExtensionSessionTest-defaultfail")
        try {
            val device = RegularOnlyDevice()
            val driver = FakeDriver(device)
            val listener = RecordingListener()
            val session = Camera2ExtensionSession(driver, executor, snapshot(), listener = listener)
            session.open("0", mock(Surface::class.java), FrameSize(1280, 720))
            executor.drain()
            assertTrue(!device.regularSessionRequested)
            assertEquals(SessionState.OPEN, session.state)
            assertTrue(listener.errors.any { it.contains("configure failed") })
        } finally {
            executor.shutdown()
        }
    }

    @Test fun staleOpenCompletionIsDroppedAndDeviceClosed() {
        val executor = Camera2Executor("ExtensionSessionTest-stale")
        try {
            var pending: DeviceCallback? = null
            val driver = object : Camera2Driver {
                override fun openCamera(cameraId: String, callback: DeviceCallback) {
                    pending = callback
                }
                override fun createStillOutput(size: FrameSize): StillOutput? = FakeStillOutput()
            }
            val session = Camera2ExtensionSession(driver, executor, snapshot())
            session.open("0", mock(Surface::class.java), FrameSize(1280, 720))
            executor.drain()
            assertEquals(SessionState.OPENING, session.state)
            session.close()
            executor.drain()
            assertEquals(SessionState.CLOSED, session.state)

            val late = FakeDeviceHandle()
            pending!!.onOpened(late)
            executor.drain()
            assertEquals(SessionState.CLOSED, session.state)
            assertTrue(late.closed, "stale device must be closed")
            assertEquals(null, late.extensionSurfaces)
        } finally {
            executor.shutdown()
        }
    }
}

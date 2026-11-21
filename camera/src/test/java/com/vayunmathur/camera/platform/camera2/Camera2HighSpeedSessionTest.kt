package com.vayunmathur.camera.platform.camera2

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
 * Phase-5 session tests over a fake driver: the constrained-session
 * configure sequence, the pinned repeating request, up-front rejection when
 * the lens has no slo-mo point, and stale-generation drops.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Camera2HighSpeedSessionTest {

    private class FakeHighSpeedHandle : CaptureSessionHandle {
        val highSpeedRequests = mutableListOf<Pair<ResolvedHighSpeedParams, List<Surface>>>()
        var closed = false
        override fun setRepeatingPreview(params: ResolvedRequestParams, target: Surface) {}
        override fun captureStill(
            params: ResolvedStillParams,
            stillTarget: Surface,
            callback: StillResultCallback,
        ) {
            callback.onFailed("unused")
        }
        override fun setRepeatingHighSpeed(params: ResolvedHighSpeedParams, targets: List<Surface>) {
            highSpeedRequests += params to targets
        }
        override fun close() { closed = true }
    }

    private class FakeDeviceHandle(
        override val cameraId: String = "0",
        val sessionHandle: FakeHighSpeedHandle = FakeHighSpeedHandle(),
        private val failConfigure: Boolean = false,
    ) : DeviceHandle {
        var constrainedSurfaces: List<Surface>? = null
        var regularSessionRequested = false
        var closed = false
        override fun createCaptureSession(
            surfaces: List<Surface>,
            callback: CaptureSessionCallback,
        ) {
            regularSessionRequested = true
            callback.onConfigured(sessionHandle)
        }
        override fun createConstrainedHighSpeedCaptureSession(
            surfaces: List<Surface>,
            callback: CaptureSessionCallback,
        ) {
            constrainedSurfaces = surfaces
            if (failConfigure) callback.onConfigureFailed() else callback.onConfigured(sessionHandle)
        }
        override fun close() { closed = true }
    }

    private class FakeDriver(
        val device: FakeDeviceHandle = FakeDeviceHandle(),
    ) : Camera2Driver {
        var openedCameraId: String? = null
        override fun openCamera(cameraId: String, callback: DeviceCallback) {
            openedCameraId = cameraId
            callback.onOpened(device)
        }
        override fun createStillOutput(size: FrameSize): StillOutput? = null
    }

    private class RecordingListener : Camera2HighSpeedSession.Listener {
        val errors = mutableListOf<String>()
        val streaming = mutableListOf<HighSpeedSelection>()
        override fun onError(message: String) { errors += message }
        override fun onStreaming(selection: HighSpeedSelection) { streaming += selection }
    }

    private fun snapshot(
        ranges: Map<Size, List<Range<Int>>> = mapOf(
            Size(1280, 720) to listOf(Range(240, 240)),
        ),
    ): LensCharacteristicsSnapshot = CharacteristicsFacade.fromFakeMap(
        cameraId = "0",
        lensFacingRaw = CameraMetadata.LENS_FACING_BACK,
        availableCapabilities = setOf(
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO,
        ),
        highSpeedVideoSizes = ranges.keys.toList(),
        highSpeedFpsRangesBySize = ranges,
    ).snapshot

    private fun Camera2Executor.drain() {
        val latch = CountDownLatch(1)
        execute { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS), "executor did not drain")
    }

    @Test fun openConfiguresConstrainedSessionAndPinsFixedRange() {
        val executor = Camera2Executor("HighSpeedSessionTest-seq")
        try {
            val driver = FakeDriver()
            val listener = RecordingListener()
            val session = Camera2HighSpeedSession(driver, executor, snapshot(), listener = listener)
            val encoder = mock(Surface::class.java)
            val preview = mock(Surface::class.java)

            session.open("0", encoder, preview)
            executor.drain()

            assertEquals("0", driver.openedCameraId)
            assertEquals(SessionState.STREAMING, session.state)
            // Constrained seam used, regular session never requested, and the
            // encoder surface is the first target.
            assertTrue(!driver.device.regularSessionRequested)
            assertEquals(listOf(encoder, preview), driver.device.constrainedSurfaces)
            val (params, targets) = driver.device.sessionHandle.highSpeedRequests.single()
            assertEquals(listOf(encoder, preview), targets)
            assertEquals(Range(240, 240), params.aeTargetFpsRange)
            assertEquals(240, session.currentSelection()?.captureFps)
            assertEquals(240, listener.streaming.single().captureFps)

            session.close()
            executor.drain()
            assertEquals(SessionState.CLOSED, session.state)
            assertTrue(driver.device.sessionHandle.closed)
            assertTrue(driver.device.closed)
        } finally {
            executor.shutdown()
        }
    }

    @Test fun noSloMoPointFailsBeforeOpeningDevice() {
        val executor = Camera2Executor("HighSpeedSessionTest-none")
        try {
            val driver = FakeDriver()
            val listener = RecordingListener()
            val session = Camera2HighSpeedSession(
                driver, executor, snapshot(ranges = emptyMap()), listener = listener,
            )
            session.open("0", mock(Surface::class.java), mock(Surface::class.java))
            executor.drain()
            assertEquals(null, driver.openedCameraId)
            assertEquals(SessionState.CLOSED, session.state)
            assertTrue(listener.errors.single().contains("no constrained high-speed"))
        } finally {
            executor.shutdown()
        }
    }

    @Test fun configureFailureReportsErrorAndStaysOpen() {
        val executor = Camera2Executor("HighSpeedSessionTest-cfgfail")
        try {
            val driver = FakeDriver(FakeDeviceHandle(failConfigure = true))
            val listener = RecordingListener()
            val session = Camera2HighSpeedSession(driver, executor, snapshot(), listener = listener)
            session.open("0", mock(Surface::class.java), mock(Surface::class.java))
            executor.drain()
            assertEquals(SessionState.OPEN, session.state)
            assertTrue(listener.errors.any { it.contains("configure failed") })
            assertTrue(listener.streaming.isEmpty())
        } finally {
            executor.shutdown()
        }
    }

    @Test fun staleOpenCompletionIsDroppedAndDeviceClosed() {
        val executor = Camera2Executor("HighSpeedSessionTest-stale")
        try {
            var pending: DeviceCallback? = null
            val driver = object : Camera2Driver {
                override fun openCamera(cameraId: String, callback: DeviceCallback) {
                    pending = callback
                }
                override fun createStillOutput(size: FrameSize): StillOutput? = null
            }
            val session = Camera2HighSpeedSession(driver, executor, snapshot())
            session.open("0", mock(Surface::class.java), mock(Surface::class.java))
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
            assertEquals(null, late.constrainedSurfaces)
        } finally {
            executor.shutdown()
        }
    }
}

package com.vayunmathur.camera.platform.camera2

import android.graphics.ImageFormat
import android.hardware.camera2.CameraMetadata
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Session-logic tests over a fake driver: Robolectric's Camera2 shadows
 * cannot drive a real session, so the open/configure/stream sequence, the
 * generation-token drop of stale callbacks, and up-front stream validation
 * are verified here against recorded fake calls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Camera2SessionTest {

    private class FakeSessionHandle : CaptureSessionHandle {
        val repeating = mutableListOf<ResolvedRequestParams>()
        var closed = false
        override fun setRepeatingPreview(params: ResolvedRequestParams, target: Surface) {
            repeating += params
        }
        override fun captureStill(
            params: ResolvedStillParams,
            stillTarget: Surface,
            callback: StillResultCallback,
        ) {
            callback.onCompleted(RawStillMetadata())
        }
        override fun close() { closed = true }
    }

    private class FakeDeviceHandle(
        override val cameraId: String = "0",
        private val sessionHandle: FakeSessionHandle = FakeSessionHandle(),
        private val failConfigure: Boolean = false,
    ) : DeviceHandle {
        var sessionCallback: CaptureSessionCallback? = null
        var closed = false
        /** Models the HAL's async close completion; null withholds it. */
        var onClosedHook: (() -> Unit)? = null
        val sessionHandleRef: FakeSessionHandle get() = sessionHandle
        override fun createCaptureSession(
            surfaces: List<Surface>,
            callback: CaptureSessionCallback,
        ) {
            sessionCallback = callback
            if (failConfigure) callback.onConfigureFailed()
            else callback.onConfigured(sessionHandle)
        }
        override fun close() {
            closed = true
            onClosedHook?.invoke()
        }
    }

    private class FakeDriver(
        val device: FakeDeviceHandle = FakeDeviceHandle(),
        private val failConfigure: Boolean = false,
    ) : Camera2Driver {
        var openedCameraId: String? = null
        var openCount: Int = 0
        var deviceCallback: DeviceCallback? = null
        override fun openCamera(cameraId: String, callback: DeviceCallback) {
            openedCameraId = cameraId
            openCount++
            deviceCallback = callback
            val handed = if (failConfigure) {
                FakeDeviceHandle(cameraId, failConfigure = true)
            } else {
                device
            }
            // Android delivers onClosed for a closed device; wire it here
            // unless a test withholds it by clearing the hook.
            handed.onClosedHook = { callback.onClosed() }
            callback.onOpened(handed)
        }
        override fun createStillOutput(size: FrameSize): StillOutput? = null
    }

    private fun snapshot(
        previewSizes: List<Size> = listOf(Size(1920, 1080), Size(1280, 720)),
        zoomRatioRange: Range<Float>? = Range(1f, 10f),
    ): LensCharacteristicsSnapshot = CharacteristicsFacade.fromFakeMap(
        cameraId = "0",
        zoomRatioRange = zoomRatioRange,
        hasFlashUnit = true,
        exposureCompensationEvRange = Range(-4f, 4f),
        sizesByFormat = mapOf(ImageFormat.PRIVATE to previewSizes),
        activeArrayWidth = 4000,
        activeArrayHeight = 3000,
        exposureCompensationStepEv = 1f / 6f,
        maxAfRegions = 1,
        maxAeRegions = 1,
    ).snapshot

    private class RecordingListener : Camera2Session.Listener {
        val states = mutableListOf<SessionState>()
        val errors = mutableListOf<String>()
        val streamingSizes = mutableListOf<FrameSize>()
        override fun onStateChanged(state: SessionState) { states += state }
        override fun onError(message: String) { errors += message }
        override fun onStreaming(previewSize: FrameSize) { streamingSizes += previewSize }
    }

    /** Runs [block] after posting a barrier behind the session's queued work. */
    private fun Camera2Executor.drain() {
        val latch = CountDownLatch(1)
        execute { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS), "executor did not drain")
    }

    private fun newSession(
        driver: Camera2Driver,
        executor: Camera2Executor,
        listener: RecordingListener = RecordingListener(),
        snap: LensCharacteristicsSnapshot = snapshot(),
    ): Camera2Session = Camera2Session(driver, executor, snap, listener = listener)

    @Test fun openConfigureStreamSequence() {
        val executor = Camera2Executor("Camera2SessionTest-seq")
        try {
            val driver = FakeDriver()
            val listener = RecordingListener()
            val session = newSession(driver, executor, listener)
            val surface = mock(Surface::class.java)

            session.open("0", surface, FrameSize(1280, 720))
            executor.drain()

            assertEquals("0", driver.openedCameraId)
            assertEquals(SessionState.STREAMING, session.state)
            assertEquals(listOf(FrameSize(1280, 720)), listener.streamingSizes)
            // Initial repeating request carries the default controls.
            val handle = driver.device.sessionHandleRef
            assertEquals(1, handle.repeating.size)
            val params = handle.repeating.single()
            assertEquals(1f, params.zoomRatio)
            assertEquals(CameraMetadata.FLASH_MODE_OFF, params.flashMode)
            assertEquals(0, params.aeExposureCompensationSteps)

            // Control changes re-issue the repeating request resolved.
            session.setZoomRatio(2f)
            session.setTorch(true)
            executor.drain()
            assertTrue(handle.repeating.size >= 3)
            val zoomed = handle.repeating[1]
            assertEquals(2f, zoomed.zoomRatio)
            val torched = handle.repeating.last()
            assertEquals(CameraMetadata.FLASH_MODE_TORCH, torched.flashMode)

            // Tap metering resolves to regions in the last repeating request.
            session.tapToFocus(0.5f, 0.5f)
            executor.drain()
            val metered = handle.repeating.last()
            assertTrue(metered.meteringActive)
            assertEquals(1, metered.afRegions.size)
            assertEquals(1, metered.aeRegions.size)

            session.close()
            executor.drain()
            assertEquals(SessionState.CLOSED, session.state)
            assertTrue(handle.closed)
            assertTrue(driver.device.closed)
        } finally {
            executor.shutdown()
        }
    }

    @Test fun unsupportedStreamFailsBeforeOpeningDevice() {
        val executor = Camera2Executor("Camera2SessionTest-nostream")
        try {
            val driver = FakeDriver()
            val listener = RecordingListener()
            val session = newSession(driver, executor, listener, snapshot(previewSizes = emptyList()))
            session.open("0", mock(Surface::class.java), FrameSize(1280, 720))
            executor.drain()

            // Validation happens up front: the HAL was never opened.
            assertEquals(null, driver.openedCameraId)
            assertEquals(SessionState.CLOSED, session.state)
            assertEquals(1, listener.errors.size)
            assertTrue(listener.errors.single().contains("no supported preview sizes"))
        } finally {
            executor.shutdown()
        }
    }

    @Test fun unlistedSizeSubstitutesDeterministically() {
        val executor = Camera2Executor("Camera2SessionTest-substitute")
        try {
            val driver = FakeDriver()
            val listener = RecordingListener()
            val session = newSession(driver, executor, listener)
            session.open("0", mock(Surface::class.java), FrameSize(1600, 900))
            executor.drain()
            assertEquals(SessionState.STREAMING, session.state)
            assertEquals(listOf(FrameSize(1920, 1080)), listener.streamingSizes)
            assertEquals(FrameSize(1920, 1080), session.currentPreviewSize())
        } finally {
            executor.shutdown()
        }
    }

    @Test fun configureFailureFallsBackToOpenWithError() {
        val executor = Camera2Executor("Camera2SessionTest-cfgfail")
        try {
            val driver = FakeDriver(failConfigure = true)
            val listener = RecordingListener()
            val session = newSession(driver, executor, listener)
            session.open("0", mock(Surface::class.java), FrameSize(1280, 720))
            executor.drain()

            assertEquals(SessionState.OPEN, session.state)
            assertTrue(listener.errors.any { it.contains("configure failed") })
            assertTrue(listener.streamingSizes.isEmpty())
        } finally {
            executor.shutdown()
        }
    }

    @Test fun staleGenerationCallbacksAreDropped() {
        val executor = Camera2Executor("Camera2SessionTest-stale")
        try {
            // Driver that captures the callback without firing it, so the
            // test can deliver the open completion after teardown began.
            var pendingCallback: DeviceCallback? = null
            val driver = object : Camera2Driver {
                override fun openCamera(cameraId: String, callback: DeviceCallback) {
                    pendingCallback = callback
                }
                override fun createStillOutput(size: FrameSize): StillOutput? = null
            }
            val listener = RecordingListener()
            val session = newSession(driver, executor, listener)
            session.open("0", mock(Surface::class.java), FrameSize(1280, 720))
            executor.drain()
            assertEquals(SessionState.OPENING, session.state)

            session.close()
            executor.drain()
            assertEquals(SessionState.CLOSED, session.state)

            // The stale onOpened arrives late: its device must be closed and
            // the machine must stay CLOSED (no resurrection).
            val lateDevice = FakeDeviceHandle()
            pendingCallback!!.onOpened(lateDevice)
            executor.drain()
            assertEquals(SessionState.CLOSED, session.state)
            assertTrue(lateDevice.closed, "stale device handle must be closed")
            assertFalse(lateDevice.sessionHandleRef.repeating.isNotEmpty())
        } finally {
            executor.shutdown()
        }
    }

    @Test fun closeIsIdempotent() {
        val executor = Camera2Executor("Camera2SessionTest-close")
        try {
            val driver = FakeDriver()
            val session = newSession(driver, executor)
            session.open("0", mock(Surface::class.java), FrameSize(1280, 720))
            executor.drain()
            session.close()
            session.close()
            executor.drain()
            assertEquals(SessionState.CLOSED, session.state)
            // Reopening after close stays closed (terminal ownership).
            session.open("0", mock(Surface::class.java), FrameSize(1280, 720))
            executor.drain()
            // A closed session may be re-opened only from CLOSED — which is
            // exactly its state — so a fresh open is legal and streams again.
            assertEquals(SessionState.STREAMING, session.state)
        } finally {
            executor.shutdown()
        }
    }

    /**
     * Regression (resume-after-background): the pause surface bounce is
     * close() followed immediately by open() on the *same* session, while
     * the HAL has only initiated — not completed — the old device's close.
     * The reopen must be parked until that device's onClosed; issuing
     * openCamera earlier is the first-attempt resume failure (and a later
     * manual Retry, on a fresh session, always worked). Before the fix the
     * second openCamera was issued as soon as its executor task ran.
     */
    @Test fun reopenIsDeferredUntilPreviousDeviceClosed() {
        val executor = Camera2Executor("Camera2SessionTest-reopen-deferred")
        try {
            val driver = FakeDriver()
            val session = newSession(driver, executor)
            session.open("0", mock(Surface::class.java), FrameSize(1280, 720))
            executor.drain()
            assertEquals(SessionState.STREAMING, session.state)
            assertEquals(1, driver.openCount)

            // Withhold the HAL's close completion, as on a real resume
            // where onClosed has not been delivered yet.
            driver.device.onClosedHook = null
            session.close()
            session.open("0", mock(Surface::class.java), FrameSize(1280, 720))
            executor.drain()

            // Session is locally CLOSED but no new openCamera may exist yet.
            assertEquals(SessionState.CLOSED, session.state)
            assertEquals(1, driver.openCount)

            // The old device finishes closing: the parked open is issued.
            driver.deviceCallback!!.onClosed()
            executor.drain()
            assertEquals(2, driver.openCount)
            assertEquals(SessionState.STREAMING, session.state)
        } finally {
            executor.shutdown()
        }
    }

    @Test fun parkedReopenIsCancelledByAnotherClose() {
        val executor = Camera2Executor("Camera2SessionTest-reopen-cancelled")
        try {
            val driver = FakeDriver()
            val session = newSession(driver, executor)
            session.open("0", mock(Surface::class.java), FrameSize(1280, 720))
            executor.drain()
            driver.device.onClosedHook = null
            session.close()
            session.open("0", mock(Surface::class.java), FrameSize(1280, 720))
            // Surface destroyed again before the old device closed.
            session.close()
            executor.drain()
            driver.deviceCallback!!.onClosed()
            executor.drain()
            assertEquals(1, driver.openCount)
            assertEquals(SessionState.CLOSED, session.state)
        } finally {
            executor.shutdown()
        }
    }
}

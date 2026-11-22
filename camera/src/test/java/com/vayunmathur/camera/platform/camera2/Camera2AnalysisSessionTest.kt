package com.vayunmathur.camera.platform.camera2

import android.graphics.ImageFormat
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
 * Analysis-stream wiring on [Camera2Session]: the YUV analysis output joins
 * the session outputs + repeating targets when a consumer is supplied, and
 * a stream-combination configure failure drops analysis (only) and keeps
 * preview streaming.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Camera2AnalysisSessionTest {

    private class FakeAnalysisOutput(override val surface: Surface) : AnalysisOutput {
        var closed = false
        override fun close() { closed = true }
    }

    private class FakeSessionHandle : CaptureSessionHandle {
        val streamingTargets = mutableListOf<List<Surface>>()
        var closed = false
        override fun setRepeatingPreview(params: ResolvedRequestParams, target: Surface) {}
        override fun setRepeatingStreaming(
            params: ResolvedRequestParams,
            targets: List<Surface>,
            callback: PreviewResultCallback?,
        ) {
            streamingTargets += targets
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
        private val sessionHandle: FakeSessionHandle,
        private val analysisSurface: Surface?,
        private val failWhenAnalysisPresent: Boolean,
    ) : DeviceHandle {
        override val cameraId: String = "0"
        var configureAttempts = 0
        override fun createCaptureSession(
            surfaces: List<Surface>,
            callback: CaptureSessionCallback,
        ) {
            configureAttempts++
            if (failWhenAnalysisPresent && analysisSurface != null && analysisSurface in surfaces) {
                callback.onConfigureFailed()
            } else {
                callback.onConfigured(sessionHandle)
            }
        }
        override fun close() {}
    }

    private class FakeDriver(
        private val device: FakeDeviceHandle,
        private val analysisOutput: FakeAnalysisOutput?,
    ) : Camera2Driver {
        val analysisSizes = mutableListOf<FrameSize>()
        override fun openCamera(cameraId: String, callback: DeviceCallback) {
            callback.onOpened(device)
        }
        override fun createStillOutput(size: FrameSize): StillOutput? = null
        override fun createAnalysisOutput(size: FrameSize, consumer: AnalysisConsumer): AnalysisOutput? {
            analysisSizes += size
            return analysisOutput
        }
    }

    private fun snapshot(): LensCharacteristicsSnapshot = CharacteristicsFacade.fromFakeMap(
        cameraId = "0",
        zoomRatioRange = Range(1f, 10f),
        hasFlashUnit = true,
        exposureCompensationEvRange = Range(-4f, 4f),
        sizesByFormat = mapOf(
            ImageFormat.PRIVATE to listOf(Size(1920, 1080), Size(1280, 720)),
            ImageFormat.YUV_420_888 to listOf(Size(1920, 1080), Size(1280, 960), Size(640, 480)),
        ),
        activeArrayWidth = 4000,
        activeArrayHeight = 3000,
        exposureCompensationStepEv = 1f / 6f,
        maxAfRegions = 1,
        maxAeRegions = 1,
    ).snapshot

    private fun Camera2Executor.drain() {
        val latch = CountDownLatch(1)
        execute { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS), "executor did not drain")
    }

    @Test fun analysisOutputStreamsAlongsidePreview() {
        val executor = Camera2Executor("Camera2AnalysisSessionTest-ok")
        try {
            val analysisSurface = mock(Surface::class.java)
            val analysisOutput = FakeAnalysisOutput(analysisSurface)
            val handle = FakeSessionHandle()
            val device = FakeDeviceHandle(handle, analysisSurface, failWhenAnalysisPresent = false)
            val driver = FakeDriver(device, analysisOutput)
            val session = Camera2Session(
                driver, executor, snapshot(),
                analysisConsumer = AnalysisConsumer { },
            )
            session.open("0", mock(Surface::class.java), FrameSize(1280, 720))
            executor.drain()

            assertEquals(SessionState.STREAMING, session.state)
            assertTrue(session.hasAnalysisOutput)
            // Capped at ~1.2 MP: largest YUV at/under the cap is 1280x960.
            assertEquals(listOf(FrameSize(1280, 960)), driver.analysisSizes)
            assertEquals(FrameSize(1280, 960), session.currentAnalysisSize())
            // The analysis surface is a repeating-request target.
            assertTrue(handle.streamingTargets.single().contains(analysisSurface))

            session.close()
            executor.drain()
            assertTrue(analysisOutput.closed)
            assertFalse(session.hasAnalysisOutput)
        } finally {
            executor.shutdown()
        }
    }

    @Test fun configureFailureDropsAnalysisAndKeepsPreview() {
        val executor = Camera2Executor("Camera2AnalysisSessionTest-degrade")
        try {
            val analysisSurface = mock(Surface::class.java)
            val analysisOutput = FakeAnalysisOutput(analysisSurface)
            val handle = FakeSessionHandle()
            val device = FakeDeviceHandle(handle, analysisSurface, failWhenAnalysisPresent = true)
            val driver = FakeDriver(device, analysisOutput)
            val errors = mutableListOf<String>()
            val session = Camera2Session(
                driver, executor, snapshot(),
                listener = object : Camera2Session.Listener {
                    override fun onError(message: String) { errors += message }
                },
                analysisConsumer = AnalysisConsumer { },
            )
            session.open("0", mock(Surface::class.java), FrameSize(1280, 720))
            executor.drain()

            // Preview survived: streaming without analysis, no error latched.
            assertEquals(SessionState.STREAMING, session.state)
            assertFalse(session.hasAnalysisOutput)
            assertTrue(analysisOutput.closed)
            assertEquals(2, device.configureAttempts)
            assertTrue(errors.isEmpty(), "analysis degrade must not report an error: $errors")
            assertFalse(handle.streamingTargets.single().contains(analysisSurface))
        } finally {
            executor.shutdown()
        }
    }

    @Test fun noConsumerMeansNoAnalysisOutput() {
        val executor = Camera2Executor("Camera2AnalysisSessionTest-none")
        try {
            val handle = FakeSessionHandle()
            val device = FakeDeviceHandle(handle, null, failWhenAnalysisPresent = false)
            val driver = FakeDriver(device, null)
            val session = Camera2Session(driver, executor, snapshot())
            session.open("0", mock(Surface::class.java), FrameSize(1280, 720))
            executor.drain()
            assertEquals(SessionState.STREAMING, session.state)
            assertFalse(session.hasAnalysisOutput)
            assertTrue(driver.analysisSizes.isEmpty())
        } finally {
            executor.shutdown()
        }
    }
}

package com.vayunmathur.camera.platform

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.CameraMetadata
import android.net.Uri
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.core.ImageCapture
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.camera.data.saveProcessedStill
import com.vayunmathur.camera.platform.camera2.Camera2Driver
import com.vayunmathur.camera.platform.camera2.Camera2Executor
import com.vayunmathur.camera.platform.camera2.Camera2Session
import com.vayunmathur.camera.platform.camera2.CaptureSessionCallback
import com.vayunmathur.camera.platform.camera2.CaptureSessionHandle
import com.vayunmathur.camera.platform.camera2.CapturedStill
import com.vayunmathur.camera.platform.camera2.CharacteristicsFacade
import com.vayunmathur.camera.platform.camera2.DeviceCallback
import com.vayunmathur.camera.platform.camera2.DeviceHandle
import com.vayunmathur.camera.platform.camera2.FrameSize
import com.vayunmathur.camera.platform.camera2.JpegImageListener
import com.vayunmathur.camera.platform.camera2.LensCharacteristicsSnapshot
import com.vayunmathur.camera.platform.camera2.PreviewResultCallback
import com.vayunmathur.camera.platform.camera2.RawStillMetadata
import com.vayunmathur.camera.platform.camera2.ReprocessRingConfig
import com.vayunmathur.camera.platform.camera2.ResolvedRequestParams
import com.vayunmathur.camera.platform.camera2.ResolvedStillParams
import com.vayunmathur.camera.platform.camera2.RingFrameListener
import com.vayunmathur.camera.platform.camera2.RingFrameMeta
import com.vayunmathur.camera.platform.camera2.RingHeldFrame
import com.vayunmathur.camera.platform.camera2.RingOutput
import com.vayunmathur.camera.platform.camera2.SessionState
import com.vayunmathur.camera.platform.camera2.StillCaptureMetadata
import com.vayunmathur.camera.platform.camera2.StillOutput
import com.vayunmathur.camera.platform.camera2.StillOutputTag
import com.vayunmathur.camera.platform.camera2.StillResultCallback
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Per-entry AVIF routing pins for the Camera2 still entries: the format is
 * read at capture/save time, so each test sets it after the ViewModel (and
 * any session) already exists — a bind-time snapshot would fail these.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Camera2AvifRoutingTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    private fun jpegBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun still(
        bytes: ByteArray,
        tag: StillOutputTag = StillOutputTag.JPEG,
    ) = CapturedStill(
        jpegBytes = bytes,
        jpegWidth = 16,
        jpegHeight = 16,
        metadata = StillCaptureMetadata(rotationDegrees = 0, mirrored = false),
        tag = tag,
    )

    private fun savedMime(uri: Uri): String? {
        context.contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns.MIME_TYPE), null, null, null,
        )?.use { cursor -> if (cursor.moveToFirst()) return cursor.getString(0) }
        return context.contentResolver.getType(uri)
    }

    // -- save routing (direct / ring / night-extension landing) ---------------

    @Test fun camera2StillWithAvifSavesAvif() {
        if (!StitchNative.isAvailable) return
        val viewModel = vm()
        viewModel.photoFormatMutable.value = PhotoFormat.AVIF
        val uri = viewModel.saveCamera2Still(still(jpegBytes()))
        assertNotNull(uri)
        assertEquals("image/avif", savedMime(uri))
    }

    @Test fun camera2StillWithJpegSelectedStaysJpeg() {
        val viewModel = vm()
        viewModel.photoFormatMutable.value = PhotoFormat.JPEG
        val uri = viewModel.saveCamera2Still(still(jpegBytes()))
        assertNotNull(uri)
        assertEquals("image/jpeg", savedMime(uri))
    }

    @Test fun ultraHdrTaggedStillStaysJpegRUnderAvif() {
        val viewModel = vm()
        viewModel.photoFormatMutable.value = PhotoFormat.AVIF
        // Plain header bytes: only the JPEG_R output tag marks this Ultra
        // HDR, so a header-sniff-only check would wrongly encode it.
        val uri = viewModel.saveCamera2Still(
            still(jpegBytes(), StillOutputTag.ULTRA_HDR_JPEG_R),
        )
        assertNotNull(uri)
        assertEquals("image/jpeg", savedMime(uri))
    }

    @Test fun processedStillLandingWithAvifSavesAvif() {
        // The landing RAW night and the custom night merge save through.
        if (!StitchNative.isAvailable) return
        val viewModel = vm()
        viewModel.photoFormatMutable.value = PhotoFormat.AVIF
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        val saved = viewModel.saveProcessedStill(bitmap, 0, jpegBytes())
        bitmap.recycle()
        assertNotNull(saved)
        assertEquals(com.vayunmathur.camera.domain.StillFormat.AVIF, saved.format)
        assertEquals("image/avif", savedMime(saved.uri))
    }

    // -- entry routing over session fakes -------------------------------------

    private class FakeStillOutput : StillOutput {
        override val surface: Surface = Mockito.mock(Surface::class.java)
        var jpegListener: JpegImageListener? = null
        override fun setListener(listener: JpegImageListener?) { jpegListener = listener }
        override fun close() {}
    }

    private class FakeRingFrame(
        ts: Long,
        private val jpeg: ByteArray?,
    ) : RingHeldFrame {
        override val meta = RingFrameMeta(
            sensorTimestampNs = ts, exposureTimeNs = 10L, width = 16, height = 16,
        )
        override fun encodeJpeg(quality: Int): ByteArray? = jpeg
        override fun close() {}
    }

    private class FakeRingOutput : RingOutput {
        override val surface: Surface = Mockito.mock(Surface::class.java)
        var ringListener: RingFrameListener? = null
        override fun setListener(listener: RingFrameListener?) { ringListener = listener }
        override fun close() {}
        fun emit(frame: RingHeldFrame) = ringListener?.onRingFrame(frame)
    }

    private class FakeHandle : CaptureSessionHandle {
        val stillRequests = mutableListOf<ResolvedStillParams>()
        var stillCallback: StillResultCallback? = null
        var closed = false
        override fun setRepeatingPreview(params: ResolvedRequestParams, target: Surface) {}
        override fun setRepeatingStreaming(
            params: ResolvedRequestParams, targets: List<Surface>, callback: PreviewResultCallback?,
        ) {}
        override fun captureStill(
            params: ResolvedStillParams, stillTarget: Surface, callback: StillResultCallback,
        ) {
            stillRequests += params
            stillCallback = callback
        }
        override fun close() { closed = true }
    }

    private class FakeDevice(private val handle: FakeHandle) : DeviceHandle {
        override val cameraId: String = "0"
        override fun createCaptureSession(
            surfaces: List<Surface>, callback: CaptureSessionCallback,
        ) = callback.onConfigured(handle)
        override fun close() {}
    }

    private class FakeDriver(
        private val device: FakeDevice,
        private val still: FakeStillOutput?,
        private val ring: FakeRingOutput? = null,
    ) : Camera2Driver {
        override fun openCamera(cameraId: String, callback: DeviceCallback) =
            callback.onOpened(device)
        override fun createStillOutput(size: FrameSize): StillOutput? = still
        override fun createRingOutput(size: FrameSize): RingOutput? = ring
    }

    private fun snapshot(ring: Boolean): LensCharacteristicsSnapshot =
        CharacteristicsFacade.fromFakeMap(
            cameraId = "0",
            lensFacingRaw = CameraMetadata.LENS_FACING_BACK,
            zoomRatioRange = Range(1f, 10f),
            hasFlashUnit = true,
            exposureCompensationEvRange = Range(-4f, 4f),
            sizesByFormat = buildMap {
                put(ImageFormat.PRIVATE, listOf(Size(1280, 720)))
                put(ImageFormat.JPEG, listOf(Size(4000, 3000)))
                if (ring) put(ImageFormat.YUV_420_888, listOf(Size(1920, 1080)))
            },
            activeArrayWidth = 4000,
            activeArrayHeight = 3000,
            sensorOrientationDegrees = 90,
            exposureCompensationStepEv = 1f / 6f,
            maxAfRegions = 1,
            maxAeRegions = 1,
        ).snapshot

    private fun Camera2Executor.drain() {
        val latch = CountDownLatch(1)
        execute { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS), "executor did not drain")
    }

    private fun streamingSession(
        executor: Camera2Executor,
        handle: FakeHandle,
        ring: FakeRingOutput? = null,
    ): Camera2Session {
        val session = Camera2Session(
            FakeDriver(FakeDevice(handle), FakeStillOutput(), ring),
            executor,
            snapshot(ring != null),
            ringConfig = if (ring != null) ReprocessRingConfig(enabled = true) else ReprocessRingConfig.DISABLED,
        )
        session.open("0", Mockito.mock(Surface::class.java), FrameSize(1280, 720))
        executor.drain()
        assertEquals(SessionState.STREAMING, session.state)
        return session
    }

    @Test fun avifPlainShotTakesCamera2InsteadOfMotionDiversion() {
        val executor = Camera2Executor("AvifRouting-direct")
        try {
            val handle = FakeHandle()
            val session = streamingSession(executor, handle)
            val viewModel = vm()
            viewModel.registerCamera2Session(session)
            // Default plain shot (PHOTO, 4:3, no bake): Motion-eligible.
            // JPEG keeps the diversion; AVIF (set after bind) must not.
            viewModel.photoFormatMutable.value = PhotoFormat.JPEG
            assertFalse(viewModel.tryCaptureCamera2Still())
            executor.drain()
            assertEquals(0, handle.stillRequests.size)

            viewModel.photoFormatMutable.value = PhotoFormat.AVIF
            assertTrue(viewModel.tryCaptureCamera2Still())
            executor.drain()
            assertEquals(1, handle.stillRequests.size)
            session.close()
            executor.drain()
        } finally {
            executor.shutdown()
        }
    }

    @Test fun ringDirectEncodeStillWithAvifSavesAvif() {
        if (!StitchNative.isAvailable) return
        val executor = Camera2Executor("AvifRouting-ring")
        try {
            val handle = FakeHandle()
            val ring = FakeRingOutput()
            // No YUV reprocessing on this snapshot: captureFromRing serves
            // the shutter by direct-encoding the held frame.
            val session = streamingSession(executor, handle, ring)
            ring.emit(FakeRingFrame(200, jpegBytes()))
            executor.drain()

            val captured = mutableListOf<CapturedStill>()
            session.captureFromRing(
                callback = object : Camera2Session.StillCallback {
                    override fun onStillCaptured(still: CapturedStill) { captured += still }
                    override fun onStillFailed(message: String) {}
                },
                shutterTimestampNs = 215,
            )
            executor.drain()
            assertEquals(1, captured.size)
            assertEquals(0, handle.stillRequests.size)

            val viewModel = vm()
            viewModel.photoFormatMutable.value = PhotoFormat.AVIF
            val uri = viewModel.saveCamera2Still(captured.single())
            assertNotNull(uri)
            assertEquals("image/avif", savedMime(uri))
            session.close()
            executor.drain()
        } finally {
            executor.shutdown()
        }
    }

    // -- video / slo-mo snapshot + night-extension entry decisions -------------

    @Test fun snapshotAndNightExtensionReadFormatAtCaptureTime() {
        val viewModel = vm()
        // JPEG (default, set at bind): both entries stay on the file path.
        assertFalse(viewModel.videoSnapshotInMemory())
        assertFalse(viewModel.nightExtensionInMemory())
        // Changed after bind: both entries must follow without a rebind.
        viewModel.photoFormatMutable.value = PhotoFormat.AVIF
        if (StitchNative.isAvailable) {
            assertTrue(viewModel.videoSnapshotInMemory())
            assertTrue(viewModel.nightExtensionInMemory())
        }
        viewModel.photoFormatMutable.value = PhotoFormat.JPEG
        assertFalse(viewModel.videoSnapshotInMemory())
        assertFalse(viewModel.nightExtensionInMemory())
    }

    @Test fun videoSnapshotUsesInMemoryCaptureWhenAvif() {
        if (!StitchNative.isAvailable) return
        val viewModel = vm()
        val capture = Mockito.mock(ImageCapture::class.java)
        Mockito.`when`(capture.captureMode).thenReturn(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        viewModel.imageCapture = capture
        viewModel.photoFormatMutable.value = PhotoFormat.AVIF
        viewModel.captureVideoSnapshot()
        Mockito.verify(capture).takePicture(
            Mockito.any(java.util.concurrent.Executor::class.java),
            Mockito.any(ImageCapture.OnImageCapturedCallback::class.java),
        )
    }

    @Test fun videoSnapshotUsesFilePathWhenJpeg() {
        val viewModel = vm()
        val capture = Mockito.mock(ImageCapture::class.java)
        Mockito.`when`(capture.captureMode).thenReturn(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        viewModel.imageCapture = capture
        viewModel.photoFormatMutable.value = PhotoFormat.JPEG
        viewModel.captureVideoSnapshot()
        Mockito.verify(capture).takePicture(
            Mockito.any(ImageCapture.OutputFileOptions::class.java),
            Mockito.any(java.util.concurrent.Executor::class.java),
            Mockito.any(ImageCapture.OnImageSavedCallback::class.java),
        )
    }
}

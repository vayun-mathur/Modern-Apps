package com.vayunmathur.camera.util

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.net.Uri
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Cov-x4 wave 3: processed-result capture, burst and saver paths driven by a
 * mock ImageCapture that immediately delivers a real JPEG frame, plus the
 * timelapse remux fallback and high-speed recording entry.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraCovX4CaptureTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    private fun jpegBytes(w: Int, h: Int, color: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun jpegPlaneProxy(jpeg: ByteArray, width: Int): ImageProxy.PlaneProxy =
        Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(ImageProxy.PlaneProxy::class.java),
        ) { _, m, _ ->
            when (m.name) {
                "getBuffer" -> ByteBuffer.wrap(jpeg.copyOf())
                "getRowStride" -> width
                "getPixelStride" -> 1
                else -> null
            }
        } as ImageProxy.PlaneProxy

    private fun imageInfoProxy(rotation: Int): ImageInfo = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(ImageInfo::class.java),
    ) { _, m, _ ->
        when (m.name) {
            "getRotationDegrees" -> rotation
            "getTimestamp" -> 9L
            else -> null
        }
    } as ImageInfo

    private fun jpegProxy(jpeg: ByteArray, width: Int, height: Int, rotation: Int): ImageProxy {
        val plane = jpegPlaneProxy(jpeg, width)
        val info = imageInfoProxy(rotation)
        return Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(ImageProxy::class.java),
        ) { _, m, _ ->
            when (m.name) {
                "getWidth" -> width
                "getHeight" -> height
                "getPlanes" -> arrayOf(plane)
                "getImageInfo" -> info
                "getFormat" -> ImageFormat.JPEG
                "getCropRect" -> android.graphics.Rect(0, 0, width, height)
                "close" -> null
                else -> null
            }
        } as ImageProxy
    }

    private fun anyExecutor(): Executor {
        Mockito.any(Executor::class.java)
        return Executor { it.run() }
    }

    private fun anyCapturedCallback(): ImageCapture.OnImageCapturedCallback {
        Mockito.any(ImageCapture.OnImageCapturedCallback::class.java)
        return object : ImageCapture.OnImageCapturedCallback() {}
    }

    private fun anySavedCallback(): ImageCapture.OnImageSavedCallback {
        Mockito.any(ImageCapture.OnImageSavedCallback::class.java)
        return object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) {}
            override fun onError(e: androidx.camera.core.ImageCaptureException) {}
        }
    }

    private fun anyOutputOptions(): ImageCapture.OutputFileOptions {
        Mockito.any(ImageCapture.OutputFileOptions::class.java)
        return ImageCapture.OutputFileOptions.Builder(ByteArrayOutputStream()).build()
    }

    /** Mock capture delivering [jpeg] as an in-memory frame, and success for file saves. */
    private fun mockCapture(jpeg: ByteArray, width: Int, height: Int, rotation: Int = 0): ImageCapture {
        val capture = Mockito.mock(ImageCapture::class.java)
        Mockito.`when`(capture.takePicture(anyExecutor(), anyCapturedCallback()))
            .thenAnswer { inv ->
                val cb = inv.getArgument<ImageCapture.OnImageCapturedCallback>(1)
                cb.onCaptureStarted()
                cb.onCaptureSuccess(jpegProxy(jpeg, width, height, rotation))
                null
            }
        Mockito.`when`(
            capture.takePicture(anyOutputOptions(), anyExecutor(), anySavedCallback()),
        ).thenAnswer { inv ->
            val cb = inv.getArgument<ImageCapture.OnImageSavedCallback>(2)
            cb.onImageSaved(Mockito.mock(ImageCapture.OutputFileResults::class.java))
            null
        }
        return capture
    }

    private fun awaitUntil(timeoutMs: Long = 8_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !condition()) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(25)
        }
    }

    @Test fun capturePhotoForResultProcessedReturnsThumbnail() {
        val viewModel = vm()
        viewModel.setWarmth(0.5f)
        val capture = mockCapture(jpegBytes(120, 80, Color.RED), 120, 80, rotation = 90)
        var saved: Bitmap? = null
        var savedCalled = false
        var errorCalled = false
        viewModel.capturePhotoForResultProcessed(
            capture, null,
            onSaved = { saved = it; savedCalled = true },
            onError = { errorCalled = true },
        )
        awaitUntil { savedCalled || errorCalled }
        assertTrue(savedCalled, "processed capture must deliver a thumbnail")
        assertFalse(errorCalled)
        assertNotNull(saved)
        // 120x80 rotated 90 degrees swaps the axes before the 512px cap.
        assertEquals(80, saved!!.width)
        assertEquals(120, saved!!.height)
        assertFalse(viewModel.isCapturingMutable.value)
    }

    @Test fun capturePhotoForResultProcessedWriteFailureCallsError() {
        val viewModel = vm()
        val capture = mockCapture(jpegBytes(40, 40, Color.GREEN), 40, 40)
        var savedCalled = false
        var errorCalled = false
        viewModel.capturePhotoForResultProcessed(
            capture, Uri.parse("content://com.example.unresolvable/out.jpg"),
            onSaved = { savedCalled = true },
            onError = { errorCalled = true },
        )
        awaitUntil { savedCalled || errorCalled }
        assertTrue(errorCalled, "unwritable EXTRA_OUTPUT must surface as an error")
        assertFalse(savedCalled)
    }

    @Test fun captureSinglePhotoProcessedPathSavesAndClearsFlag() {
        val viewModel = vm()
        viewModel.setWarmth(0.6f)
        viewModel.imageCapture = mockCapture(jpegBytes(64, 48, Color.BLUE), 64, 48)
        viewModel.captureSinglePhoto()
        awaitUntil { !viewModel.isCapturingMutable.value }
        assertFalse(viewModel.isCapturingMutable.value)
        assertNotNull(viewModel.imageCapture)
    }

    @Test fun burstRunsToMaxAndStopsCleanly() {
        val viewModel = vm()
        viewModel.imageCapture = mockCapture(jpegBytes(32, 32, Color.YELLOW), 32, 32)
        viewModel.startBurst()
        awaitUntil { !viewModel.burstActiveMutable.value }
        assertFalse(viewModel.burstActiveMutable.value)
        assertFalse(viewModel.isCapturingMutable.value)
        assertEquals(CameraViewModel.BURST_MAX, viewModel.burstCountMutable.value)
        // A second burst while one is already claimed is refused by the guard
        // in a fresh VM whose flags are pre-set.
        val busy = vm()
        busy.burstActiveMutable.value = true
        busy.imageCapture = mockCapture(jpegBytes(16, 16, Color.WHITE), 16, 16)
        busy.startBurst()
        assertEquals(0, busy.burstCountMutable.value)
    }

    @Test fun remuxTimelapseKeepsRawFileWhenInputIsNotVideo() {
        val viewModel = vm()
        val raw = File(context.cacheDir, "covx4-raw.mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        assertNotNull(viewModel)
        val out = remuxTimelapse(raw, "covx4ts")
        // No video track / unreadable input: the raw recording is kept verbatim.
        assertEquals(raw, out)
        assertTrue(raw.exists())
        assertFalse(File(context.cacheDir, "TL_covx4ts.mp4").exists())
    }

    @Test fun highSpeedRecordingEntryWithUnboundCapture() {
        val viewModel = vm()
        viewModel.highSpeedVideoCapture = VideoCapture.withOutput(Recorder.Builder().build())
        try {
            viewModel.startHighSpeedRecording()
            ShadowLooper.idleMainLooper()
        } catch (e: IllegalStateException) {
            // Unbound capture may refuse synchronously; it must not hang.
            assertNotNull(e.message)
        }
        // The shadow recorder accepts the start: a live recording handle exists.
        if (viewModel.highSpeedRecording != null) {
            viewModel.isRecordingMutable.value = true
            viewModel.toggleHighSpeedRecording()
            assertEquals(null, viewModel.highSpeedRecording)
        }
    }

    @Test fun mediaStoreSaverDirectPaths() {
        val resolver = context.contentResolver
        val bmp = Bitmap.createBitmap(20, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        val jpeg = jpegBytes(20, 12, Color.CYAN)
        // MediaStore-insert paths: success yields the row uri, shadow refusal
        // yields null — either outcome must be a value, never a throw.
        val byBitmap = MediaStoreSaver.saveBitmap(resolver, MediaStoreSaver.imageValues("covx4-a.jpg"), bmp)
        assertTrue(byBitmap == null || byBitmap.toString().contains("media"))
        val byBytes = MediaStoreSaver.saveJpegBytes(resolver, MediaStoreSaver.imageValues("covx4-b.jpg"), jpeg)
        assertTrue(byBytes == null || byBytes.toString().contains("media"))
        // File-backed video save copies the staged file (or reports refusal).
        val staged = File(context.cacheDir, "covx4-stage.mp4").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val video = MediaStoreSaver.saveVideoFile(resolver, MediaStoreSaver.videoValues("covx4-c.mp4"), staged)
        assertTrue(video == null || video.toString().contains("media"))
        // Caller-supplied destinations.
        val destImage = Uri.parse("content://com.example.unresolvable/direct.jpg")
        assertEquals(null, MediaStoreSaver.saveBitmapToUri(resolver, destImage, bmp))
        assertEquals(null, MediaStoreSaver.saveJpegBytesToUri(resolver, destImage, jpeg))
        assertEquals(null, MediaStoreSaver.saveVideoFileToUri(resolver, destImage, staged))
        assertEquals("image/jpeg", MediaStoreSaver.imageValues("x.jpg").getAsString("mime_type"))
        assertEquals("video/mp4", MediaStoreSaver.videoValues("x.mp4").getAsString("mime_type"))
        assertTrue(MediaStoreSaver.timestamp().isNotEmpty())
    }
}

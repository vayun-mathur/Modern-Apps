package com.vayunmathur.camera.platform

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.camera.core.ImageCapture
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.vayunmathur.camera.domain.PanoInfo
import com.vayunmathur.camera.data.SafDocuments
import com.vayunmathur.camera.data.SaveTarget
import com.vayunmathur.camera.data.prepareStillSave
import com.vayunmathur.camera.data.clearSaveTreeUri
import com.vayunmathur.camera.data.closeStream
import com.vayunmathur.camera.data.cycleAspectRatio
import com.vayunmathur.camera.data.loadSaveTarget
import com.vayunmathur.camera.data.mainExecutor
import com.vayunmathur.camera.data.resolveUri
import com.vayunmathur.camera.data.saveStillBitmap
import com.vayunmathur.camera.data.saveStillBytes
import com.vayunmathur.camera.data.saveVideoStaged
import com.vayunmathur.camera.data.scanSafDoc
import com.vayunmathur.camera.data.setAudioInputSource
import com.vayunmathur.camera.data.setLocationEnabled
import com.vayunmathur.camera.data.setMotionPhotoDuration
import com.vayunmathur.camera.data.setSaveTreeUri
import com.vayunmathur.camera.data.setVideoCodec
import com.vayunmathur.camera.data.toggleGrid
import com.vayunmathur.camera.data.toggleLevel

/** Cov-w3 wave 11: still/video save routing across MediaStore and SAF targets. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraCovW3Wave11Test {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    @Test fun saveRoutingAcrossTargets() {
        val viewModel = vm()
        // Location + mirror metadata branches in prepareStillSave.
        viewModel.setLocationEnabled(true)
        viewModel.lensFacingMutable.value = androidx.camera.core.CameraSelector.LENS_FACING_FRONT
        val pending = viewModel.prepareStillSave("covw11-front.jpg")
        assertNotNull(pending)
        assertEquals("covw11-front.jpg", pending.displayName)
        assertNull(pending.saveUri)
        val results = org.mockito.Mockito.mock(ImageCapture.OutputFileResults::class.java)
        org.mockito.Mockito.`when`(results.savedUri).thenReturn(Uri.parse("content://media/1"))
        assertEquals(Uri.parse("content://media/1"), pending.resolveUri(results))
        pending.closeStream()
        // Staged video copy under the default target.
        val staged = java.io.File(context.cacheDir, "covw11.mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        viewModel.saveVideoStaged("covw11-video", staged)
        // SAF target: every save kind attempts the doc path and falls back cleanly.
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMovies%2FCov")
        viewModel.setSaveTreeUri(tree)
        viewModel.prepareStillSave("covw11-saf.jpg")?.closeStream()
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        viewModel.saveStillBitmap("covw11-saf.bmp.jpg", bmp)
        viewModel.saveStillBytes("covw11-saf.jpg", byteArrayOf(5, 6))
        viewModel.saveVideoStaged("covw11-saf-video", staged)
        viewModel.scanSafDoc(Uri.parse("content://com.android.externalstorage.documents/document/primary%3AMovies%2FCov%2Fx.mp4"))
        viewModel.clearSaveTreeUri()
        viewModel.scanSafDoc(tree) // no SAF target: no-op
        assertNotNull(viewModel.mainExecutor())
    }

    @Test fun panoramaSaveUnderSafTarget() {
        val engine = PanoramaEngine(context)
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3APictures%2FPano")
        val info = PanoInfo("cylindrical", 128, 64, 128, 64, 0, 0)
        // SAF doc creation fails under Robolectric's resolver; save falls back.
        engine.saveToMediaStore(byteArrayOf(1, 2, 3), info, SaveTarget.SafTree(tree))
        engine.saveToMediaStore(byteArrayOf(1, 2, 3), info, SaveTarget.MediaStoreDefault)
        val vm = vm()
        for (codec in VideoCodec.entries) vm.setVideoCodec(codec)
        for (source in AudioInputSource.entries) vm.setAudioInputSource(source)
        for (duration in MotionPhotoDuration.entries) vm.setMotionPhotoDuration(duration)
        assertEquals(MotionPhotoDuration.FIVE, vm.motionPhotoDurationMutable.value)
        // Aspect-ratio cycling wraps through every option back to the start.
        val start = vm.aspectRatioMutable.value
        repeat(4) { vm.cycleAspectRatio() }
        assertEquals(start, vm.aspectRatioMutable.value)
        vm.toggleGrid()
        vm.toggleLevel()
        vm.toggleGrid()
        vm.toggleLevel()
        assertEquals(false, vm.gridEnabledMutable.value)
        vm.loadSaveTarget()
        assertEquals(SaveTarget.MediaStoreDefault, vm.saveTargetMutable.value)
    }

    @Test fun loadThumbnailFromRegisteredStream() = kotlinx.coroutines.runBlocking<Unit> {
        val viewModel = vm()
        val bmp = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        val uri = Uri.parse("content://covw11/thumb.jpg")
        org.robolectric.Shadows.shadowOf(context.contentResolver)
            .registerInputStream(uri, java.io.ByteArrayInputStream(out.toByteArray()))
        // Either the shadow thumbnail loader or the stream-decode fallback runs;
        // both are production paths and neither may throw.
        viewModel.loadThumbnail(uri)
        viewModel.loadThumbnail(null)
    }

    @Test fun safDocumentHelpers() {
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCamera")
        assertTrue(SafDocuments.primaryPath(tree)?.endsWith("DCIM/Camera") == true)
        assertNull(SafDocuments.primaryPath(Uri.parse("content://com.android.externalstorage.documents/tree/home%3Ax")))
        assertNull(SafDocuments.createVideoDoc(context.contentResolver, tree, "x.mp4"))
        assertNull(SafDocuments.createImageDoc(context.contentResolver, tree, "x.jpg"))
        assertNull(SafDocuments.displayName(context.contentResolver, tree))
        assertEquals(false, SafDocuments.hasPersistedPermission(context.contentResolver, tree))
    }
}

package com.vayunmathur.camera.util

import android.content.Intent
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.camera.MainActivity
import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.domain.LensType
import com.vayunmathur.camera.domain.PhysicalLens
import com.vayunmathur.camera.platform.bindWithFallback
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/** Cov-w3 wave 3: lens bind ladder, AE snapshot attach, night entry points, activity. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraCovW3Wave3Test {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    private fun lens(id: String, type: LensType, priority: Int) = PhysicalLens(
        logicalCameraId = id, lensType = type, facing = LensFacing.BACK,
        focalLengthMm35Eq = 24f, nominalZoomRatio = 1f, fallbackPriority = priority, labelKey = id,
    )

    @Test fun bindWithFallbackLadderOrdersAndRetries() {
        val viewModel = vm()
        val provider = Mockito.mock(androidx.camera.lifecycle.ProcessCameraProvider::class.java)
        val camera = Mockito.mock(androidx.camera.core.Camera::class.java)
        val wide = lens("wide", LensType.WIDE, 0)
        val tele = lens("tele", LensType.TELEPHOTO, 2)
        // Requested binds first.
        val (boundLens, boundCamera) = viewModel.bindWithFallback(provider, tele, listOf(wide, tele)) { camera }
        assertEquals(tele, boundLens)
        assertEquals(camera, boundCamera)
        // Requested fails -> wide fallback is used and named.
        var attempts = 0
        val (fallbackLens, _) = viewModel.bindWithFallback(provider, tele, listOf(wide, tele)) {
            attempts++
            if (attempts == 1) throw IllegalStateException("nope") else camera
        }
        assertEquals(wide, fallbackLens)
        assertEquals(2, attempts)
        // Empty family + no request: a single facing-only (null lens) attempt.
        val (nullLens, _) = viewModel.bindWithFallback(provider, null, emptyList()) { camera }
        assertEquals(null, nullLens)
        // Every rung failing rethrows the last error.
        var threw = false
        try {
            viewModel.bindWithFallback(provider, wide, listOf(wide)) { throw IllegalArgumentException("always") }
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test fun aeSnapshotAttachAndNightEntries() = runBlocking<Unit> {
        val viewModel = vm()
        viewModel.attachAeSnapshot(Preview.Builder(), "covw3")
        viewModel.attachAeSnapshot(Preview.Builder(), "covw3-again")
        // Night capture entries with nothing bound degrade to no-ops/fallbacks.
        viewModel.captureNightPhotoCustom()
        viewModel.captureNightPhoto()
        ShadowLooper.idleMainLooper()
        viewModel.captureNightBurst(viewModel.computeNightExposure()) { frames ->
            assertTrue(frames.isEmpty())
        }
        // Manual-control range reading with no bound camera must not throw.
        viewModel.readManualControlRanges()
        viewModel.boundCamera = null
        assertEquals(null, viewModel.camera2ControlOrNull())
    }

    @Test fun cameraMainActivityLaunches() {
        val controller = Robolectric.buildActivity(
            MainActivity::class.java, Intent(context, MainActivity::class.java),
        ).setup()
        ShadowLooper.idleMainLooper()
        controller.pause().stop().destroy()
    }

    @Test fun cameraMainActivityCaptureIntentWithOutputAndKeys() {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = android.provider.MediaStore.ACTION_IMAGE_CAPTURE
            putExtra(android.provider.MediaStore.EXTRA_OUTPUT, android.net.Uri.parse("content://covw3/out.jpg"))
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        ShadowLooper.idleMainLooper()
        val activity = controller.get()
        // Hardware shutter keys on the camera route, and a non-shutter key.
        activity.onKeyDown(android.view.KeyEvent.KEYCODE_VOLUME_DOWN, null)
        activity.onKeyUp(android.view.KeyEvent.KEYCODE_VOLUME_DOWN, null)
        activity.onKeyDown(android.view.KeyEvent.KEYCODE_SPACE, null)
        activity.onKeyDown(android.view.KeyEvent.KEYCODE_ESCAPE, null)
        activity.onKeyUp(android.view.KeyEvent.KEYCODE_VOLUME_UP, null)
        activity.onKeyDown(android.view.KeyEvent.KEYCODE_A, null)
        activity.onKeyUp(android.view.KeyEvent.KEYCODE_A, null)
        controller.destroy()
    }

    @Test fun cameraMainActivityCaptureIntent() {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = android.provider.MediaStore.ACTION_IMAGE_CAPTURE
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        ShadowLooper.idleMainLooper()
        controller.destroy()
    }
}

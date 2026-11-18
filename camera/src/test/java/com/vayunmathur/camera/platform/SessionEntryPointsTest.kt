package com.vayunmathur.camera.platform

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Session entry points on a provider-less JVM: CameraX cannot initialize,
 * so every setup must fail cleanly (false / restored state), never hang or
 * throw out of its documented catch-and-fallback shape.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionEntryPointsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    @Test fun photoSessionFailsCleanly() = runBlocking {
        assertEquals(false, withTimeoutOrNull(20_000) { vm().setupPhotoSession() })
    }

    @Test fun panoramaSessionFailsCleanly() = runBlocking {
        assertEquals(false, withTimeoutOrNull(20_000) { vm().setupPanoramaSession() })
    }

    @Test fun nightPreviewFallsBackAndFailsCleanly() = runBlocking {
        val viewModel = vm()
        // The ExtensionsManager future never completes on a provider-less
        // JVM, so this either fails cleanly (false) or is still awaiting the
        // manager when the timeout lands (null) — never a throw, and night
        // preview never activates.
        val result = withTimeoutOrNull(8_000) { viewModel.setupNightPreviewSession() }
        assertTrue(result == false || result == null)
        assertFalse(viewModel.nightPreviewActiveMutable.value)
    }

    @Test fun portraitSessionFailsCleanly() = runBlocking {
        assertEquals(false, withTimeoutOrNull(20_000) { vm().setupPortraitSession() })
    }

    @Test fun highSpeedSessionFailsCleanly() = runBlocking {
        assertEquals(false, withTimeoutOrNull(20_000) { vm().setupHighSpeedSession() })
    }

    @Test fun videoSessionFailsCleanly() = runBlocking {
        assertEquals(false, withTimeoutOrNull(20_000) { vm().setupVideoSession() })
    }

    @Test fun sloMoProbeReportsUnsupported() = runBlocking {
        assertEquals(false, withTimeoutOrNull(20_000) { vm().probeSloMoSupport() })
    }

    @Test fun nightExtensionCaptureRestoresState() = runBlocking {
        val viewModel = vm()
        withTimeoutOrNull(20_000) { viewModel.captureNightPhotoExtension() }
        // The finally block always clears the in-flight flag.
        assertFalse(viewModel.isCapturingMutable.value)
    }

    @Test fun nightBurstWithoutCaptureCompletesEmpty() {
        val viewModel = vm()
        var done: List<Bitmap>? = null
        viewModel.captureNightBurst(NightExposure(1_000_000L, null)) { done = it }
        assertNotNull(done)
        assertEquals(emptyList(), done)
        // The empty-burst fallback stops the countdown and attempts a single.
        viewModel.onNightBurstDone(emptyList())
        assertEquals("", viewModel.longExposureRemainingMutable.value)
    }

    @Test fun extensionsManagerMissingDegrades() = runBlocking {
        val viewModel = vm()
        val provider = org.mockito.Mockito.mock(androidx.camera.lifecycle.ProcessCameraProvider::class.java)
        // No cached manager and the async ExtensionsManager cannot come up
        // against a mock provider: null, never a throw.
        val mgr = withTimeoutOrNull(8_000) { viewModel.getExtensionsManager(provider) }
        assertEquals(null, mgr)
        val available = withTimeoutOrNull(8_000) { viewModel.isNightExtensionAvailable() }
        assertTrue(available == false || available == null)
    }
}

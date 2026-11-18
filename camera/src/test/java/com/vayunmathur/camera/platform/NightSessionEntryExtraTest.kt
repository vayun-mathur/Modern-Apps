package com.vayunmathur.camera.platform

import androidx.camera.core.CameraSelector
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Night session entry points with a cached (mock) ExtensionsManager. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NightSessionEntryExtraTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    private fun anySelector(): CameraSelector =
        Mockito.any(CameraSelector::class.java) ?: CameraSelector.DEFAULT_BACK_CAMERA

    private fun mgr(available: Boolean, analysis: Boolean = false): ExtensionsManager {
        val mgr = Mockito.mock(ExtensionsManager::class.java)
        Mockito.`when`(mgr.isExtensionAvailable(anySelector(), Mockito.eq(ExtensionMode.NIGHT)))
            .thenReturn(available)
        Mockito.`when`(mgr.isImageAnalysisSupported(anySelector(), Mockito.eq(ExtensionMode.NIGHT)))
            .thenReturn(analysis)
        Mockito.`when`(mgr.getExtensionEnabledCameraSelector(anySelector(), Mockito.eq(ExtensionMode.NIGHT)))
            .thenReturn(CameraSelector.DEFAULT_BACK_CAMERA)
        return mgr
    }

    @Test fun nightPreviewUnavailableFallsBackToPhoto() = runBlocking {
        val viewModel = vm()
        viewModel.extensionsManager = mgr(available = false)
        assertEquals(false, withTimeoutOrNull(20_000) { viewModel.setupNightPreviewSession() })
        assertFalse(viewModel.nightPreviewActiveMutable.value)
    }

    @Test fun nightPreviewBindFailureFallsBack() = runBlocking {
        val viewModel = vm()
        viewModel.extensionsManager = mgr(available = true, analysis = true)
        // The extension selector resolves, but the real provider has no
        // camera to bind: setup must fall back and report false.
        assertEquals(false, withTimeoutOrNull(20_000) { viewModel.setupNightPreviewSession() })
        assertFalse(viewModel.nightPreviewActiveMutable.value)
    }

    @Test fun nightExtensionCaptureWithCachedManagerRestores() = runBlocking {
        val viewModel = vm()
        viewModel.extensionsManager = mgr(available = true, analysis = false)
        withTimeoutOrNull(20_000) { viewModel.captureNightPhotoExtension() }
        assertFalse(viewModel.isCapturingMutable.value)
    }

    @Test fun nightAvailabilityProbeWithCachedManager() = runBlocking {
        val viewModel = vm()
        viewModel.extensionsManager = mgr(available = false)
        assertEquals(false, withTimeoutOrNull(20_000) { viewModel.isNightExtensionAvailable() })
        val viewModel2 = vm()
        viewModel2.extensionsManager = mgr(available = true)
        // Available on the selector, but the session-config probe cannot be
        // satisfied without a real camera: false, never a throw.
        val result = withTimeoutOrNull(20_000) { viewModel2.isNightExtensionAvailable() }
        assertTrue(result == false || result == null)
    }

    @Test fun extensionProbesDegradeOnThrowingManager() {
        val viewModel = vm()
        val throwing = Mockito.mock(ExtensionsManager::class.java)
        Mockito.`when`(throwing.isExtensionAvailable(anySelector(), Mockito.eq(ExtensionMode.NIGHT)))
            .thenThrow(IllegalStateException("extender dead"))
        Mockito.`when`(throwing.isImageAnalysisSupported(anySelector(), Mockito.eq(ExtensionMode.NIGHT)))
            .thenThrow(IllegalArgumentException("extender dead"))
        assertFalse(viewModel.isNightExtensionSupported(throwing, CameraSelector.DEFAULT_BACK_CAMERA))
        assertFalse(viewModel.isNightAnalysisSupported(throwing, CameraSelector.DEFAULT_BACK_CAMERA))
    }
}

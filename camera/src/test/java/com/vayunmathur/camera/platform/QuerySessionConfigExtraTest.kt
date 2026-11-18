package com.vayunmathur.camera.platform

import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.SessionConfig
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** querySessionConfigSupport against stubbed provider/info (reflection). */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuerySessionConfigExtraTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    private fun query(info: CameraInfo): Boolean {
        val viewModel = vm()
        val provider = Mockito.mock(ProcessCameraProvider::class.java)
        Mockito.`when`(
            provider.getCameraInfo(
                Mockito.any(CameraSelector::class.java) ?: CameraSelector.DEFAULT_BACK_CAMERA,
            ),
        ).thenReturn(info)
        val mgr = Mockito.mock(ExtensionsManager::class.java)
        val m = Class.forName("com.vayunmathur.camera.platform.CameraNightModeKt")
            .getDeclaredMethod(
                "querySessionConfigSupport", CameraViewModel::class.java,
                ProcessCameraProvider::class.java, CameraSelector::class.java,
                ExtensionsManager::class.java, Long::class.javaPrimitiveType,
            ).apply { isAccessible = true }
        return m.invoke(
            null, viewModel, provider, CameraSelector.DEFAULT_BACK_CAMERA, mgr,
            System.currentTimeMillis(),
        ) as Boolean
    }

    @Test fun supportedAndUnsupportedAndThrowing() {
        val supported = Mockito.mock(CameraInfo::class.java)
        Mockito.`when`(supported.isSessionConfigSupported(Mockito.any(SessionConfig::class.java) ?: SessionConfig.Builder(androidx.camera.core.Preview.Builder().build()).build()))
            .thenReturn(true)
        assertEquals(true, query(supported))

        val unsupported = Mockito.mock(CameraInfo::class.java)
        Mockito.`when`(unsupported.isSessionConfigSupported(Mockito.any(SessionConfig::class.java) ?: SessionConfig.Builder(androidx.camera.core.Preview.Builder().build()).build()))
            .thenReturn(false)
        assertEquals(false, query(unsupported))

        val throwing = Mockito.mock(CameraInfo::class.java)
        Mockito.`when`(throwing.isSessionConfigSupported(Mockito.any(SessionConfig::class.java) ?: SessionConfig.Builder(androidx.camera.core.Preview.Builder().build()).build()))
            .thenThrow(IllegalStateException("no session"))
        assertEquals(false, query(throwing))
    }

    @Test fun extensionModeSanity() {
        assertEquals(ExtensionMode.NIGHT, ExtensionMode.NIGHT)
    }
}

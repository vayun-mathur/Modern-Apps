package com.vayunmathur.camera.platform

import android.graphics.Bitmap
import androidx.camera.core.ImageCapture
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Night burst driver with a Camera2 control: lock succeeds, 3A restored at the end. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Restore3AExtraTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    @Test fun driverWithControlLocksAndRestores() {
        val viewModel = vm()
        // No ImageCapture: after the lock listener fires, the driver finishes
        // immediately and restores 3A through the same control.
        val control = Mockito.mock(androidx.camera.camera2.interop.Camera2CameraControl::class.java)
        Mockito.`when`(
            control.setCaptureRequestOptions(
                Mockito.any(androidx.camera.camera2.interop.CaptureRequestOptions::class.java)
                    ?: androidx.camera.camera2.interop.CaptureRequestOptions.Builder().build(),
            ),
        ).thenReturn(Futures.immediateFuture<Void?>(null) as ListenableFuture<Void?>)

        var done: List<Bitmap>? = null
        val cls = Class.forName("com.vayunmathur.camera.platform.NightBurstDriver")
        val ctor = cls.getDeclaredConstructor(
            CameraViewModel::class.java,
            androidx.camera.camera2.interop.Camera2CameraControl::class.java,
            Boolean::class.javaPrimitiveType,
            NightExposure::class.java,
            Function1::class.java,
        ).apply { isAccessible = true }
        val onDone: (List<Bitmap>) -> Unit = { frames -> done = frames }
        val driver = ctor.newInstance(viewModel, control, false, NightExposure(2_000_000L, 1600), onDone)
        cls.getDeclaredMethod("start").apply { isAccessible = true }.invoke(driver)
        // The lock listener is posted to the main executor: idle it loose.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(emptyList(), done)
        // Lock + restore: the control saw at least two option submissions.
        Mockito.verify(control, Mockito.atLeast(2)).setCaptureRequestOptions(
            Mockito.any(androidx.camera.camera2.interop.CaptureRequestOptions::class.java)
                ?: androidx.camera.camera2.interop.CaptureRequestOptions.Builder().build(),
        )
        assertTrue(viewModel.imageCapture == null)
    }
}

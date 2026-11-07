package com.vayunmathur.games.kart.ui

import android.graphics.PixelFormat
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
class KartSurfaceViewTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun surfaceCallbacksAreSafeWithoutNativeEngine() {
        val view = KartSurfaceView(ctx)
        assertNotNull(view.holder)
        // Without a live native surface (or with one that rejects a Robolectric
        // Surface) every callback must return normally; the view catches JNI errors.
        view.surfaceCreated(view.holder)
        view.surfaceChanged(view.holder, PixelFormat.RGBA_8888, 640, 480)
        view.surfaceDestroyed(view.holder)
        // A change after destroy (not ready) is also a no-op.
        view.surfaceChanged(view.holder, PixelFormat.RGBA_8888, 320, 240)
        view.surfaceDestroyed(view.holder)
    }
}

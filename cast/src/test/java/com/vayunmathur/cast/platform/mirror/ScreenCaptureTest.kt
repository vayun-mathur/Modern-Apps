package com.vayunmathur.cast.platform.mirror

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [ShadowMediaProjectionFake::class])
class ScreenCaptureTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `start refuses when the projection creates no display and release is safe`() {
        val capture = ScreenCapture(newFakeProjection(context))
        val surface = Surface(SurfaceTexture(88))
        assertEquals(false, capture.start(surface, CaptureGeometry(640, 480, 160, 1_000_000, 30f)))
        capture.release()
        capture.release()
        surface.release()
    }
}

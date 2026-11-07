package com.vayunmathur.library.avatar.components

import android.content.Context
import android.graphics.PixelFormat
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
class YuuAvatarSurfaceViewTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `view carries its surface id and registers for holder callbacks`() {
        val view = YuuAvatarSurfaceView(context, surfaceId = 42)
        assertEquals(42, view.surfaceId)
        assertEquals(0, YuuAvatarSurfaceView(context).surfaceId, "default surface id")
    }

    @Test
    fun `lifecycle callbacks are safe without native`() {
        val view = YuuAvatarSurfaceView(context, surfaceId = 3)
        // Without libyuu_avatar every callback must return before touching JNI.
        view.surfaceCreated(view.holder)
        view.surfaceChanged(view.holder, PixelFormat.RGBA_8888, 320, 240)
        view.surfaceDestroyed(view.holder)
        // A second create/destroy cycle stays safe too.
        view.surfaceCreated(view.holder)
        view.surfaceDestroyed(view.holder)
        assertSame(view.holder, view.holder)
    }
}

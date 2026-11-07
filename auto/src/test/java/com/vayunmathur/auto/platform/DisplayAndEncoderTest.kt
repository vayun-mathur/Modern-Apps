package com.vayunmathur.auto.platform

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import android.os.Looper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DisplayAndEncoderTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun idleMain() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    @Test fun `encoder lifecycle without frames`() {
        var frames = 0
        val encoder = VideoEncoder(320, 240, 30, onFrame = { _, _ -> frames++ })
        encoder.requestKeyFrame() // no codec yet: no-op
        assertEquals(false, encoder.drain())
        encoder.start()
        // Robolectric's shadow codec may not vend an input surface; the lifecycle is the contract here
        encoder.requestKeyFrame()
        encoder.drain()
        encoder.stop()
        encoder.stop() // idempotent
        assertEquals(0, frames)
    }

    @Test fun `car display show and release renders the launcher`() {
        CarLauncherState.selectApp(null)
        CarLauncherState.setDiscovered(emptyList())
        val texture = SurfaceTexture(1)
        val surface = Surface(texture)
        val display = CarDisplay(ctx, 800, 480, 160)
        display.show(surface)
        idleMain()
        // The presentation is up: night/now-playing pushes reach shared state
        display.setNight(true)
        assertTrue(CarLauncherState.nightDark.value)
        display.startFrameInvalidation(30)
        idleMain()
        display.stopFrameInvalidation()
        // Input injection with a live decor view dispatches (returns true)
        assertTrue(display.input.injectTouch(0, listOf(Triple(10f, 10f, 0)), 0))
        assertTrue(display.input.injectKey(4, true))
        assertTrue(display.input.injectScroll(1))
        idleMain()
        display.updateConfig(1024, 600, 160)
        idleMain()
        display.release()
        idleMain()
        surface.release()
        texture.release()
        CarLauncherState.setNight(false)
    }
}

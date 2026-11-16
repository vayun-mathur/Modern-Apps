package com.vayunmathur.auto

import android.content.Context
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.auto.platform.ActiveCallInfo
import com.vayunmathur.auto.platform.CarDisplay
import com.vayunmathur.auto.platform.NowPlayingInfo
import com.vayunmathur.auto.protocol.NavSnapshot
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y3CarDisplayShowTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun showWithSurfaceAndLifecycle() {
        val display = CarDisplay(ctx, 800, 480, 160, trusted = false)
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        runCatching { display.show(surface) }
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(300); Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        display.setNowPlaying(NowPlayingInfo("Song", "Artist", true, 1_000, 60_000, null, null, true, true))
        display.hideNowPlaying()
        display.setDrivingRestricted(true); display.setDrivingRestricted(false)
        display.setParked(true); display.setParkedBrowsingGate(true); display.setParked(false)
        display.setNight(true)
        display.onNightConfigChanged(Configuration(ctx.resources.configuration).apply { uiMode = Configuration.UI_MODE_NIGHT_YES })
        display.setActiveCall(ActiveCallInfo(telecomState = 1, number = "+1", acceptedMs = 1000L))
        display.setActiveCall(null)
        runCatching { display.setNavSnapshot(NavSnapshot(latitudeE7 = 10, longitudeE7 = 20, parked = false, isNight = true)) }
        display.setMapSurfaceListener { _, _, _ -> }
        display.updateConfig(1280, 720, 240)
        display.startFrameInvalidation(30)
        Thread.sleep(200)
        display.stopFrameInvalidation()
        display.release(); display.release()
        surface.release(); texture.release()
        assertTrue(true)
    }

    @Test fun trustedDisplayShowAttempt() {
        val display = CarDisplay(ctx, 640, 480, 120, trusted = true)
        val texture = SurfaceTexture(1)
        val surface = Surface(texture)
        runCatching { display.show(surface) }
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        display.release()
        surface.release(); texture.release()
    }
}

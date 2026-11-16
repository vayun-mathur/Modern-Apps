package com.vayunmathur.auto

import android.content.ComponentName
import android.content.Context
import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.auto.platform.DiscoveredApp
import com.vayunmathur.auto.platform.HostManager
import com.vayunmathur.auto.platform.HostTemplateBus
import com.vayunmathur.library.carhost.HostTemplate
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class Y3HostManagerTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun app(pkg: String) = DiscoveredApp(ComponentName(pkg, "$pkg.Svc"), pkg, null, setOf("cat"))
    private fun idle() = Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    @Test fun focusSurfaceNightTouchAndReset() {
        val manager = HostManager(ctx)
        val a = app("com.a"); val b = app("com.b")
        manager.bindAll(listOf(a, b)); idle()
        assertTrue(manager.templateFor(a.component) is HostTemplate.Message)
        manager.setFocused(a.component); assertEquals(a.component, manager.focused.value)
        val texture = SurfaceTexture(0); val surface = Surface(texture)
        manager.forwardSurface(surface, 800, 480)
        assertFalse(manager.injectMapTouch(MotionEvent.ACTION_DOWN, 10f, 10f)) // no surface callback bound
        manager.setNight(true); manager.setNight(false)
        // Focus switch detaches the old session and re-attaches the cached surface.
        manager.setFocused(b.component); idle()
        manager.forwardSurface(null, 0, 0)
        manager.resetApp(b.component); Thread.sleep(500); idle()
        manager.resetApp(ComponentName("com.missing", "com.missing.Svc"))
        manager.setFocused(null); assertNull(manager.focused.value)
        assertFalse(manager.injectMapTouch(MotionEvent.ACTION_UP, 0f, 0f))
        manager.unbindAll(); manager.unbindAll()
        assertTrue(manager.templates.value.isEmpty())
        surface.release(); texture.release()
    }

    @Test fun templateBusMapsEveryNavigationField() {
        val nav = HostTemplate.Navigation(
            navigating = true, loading = false, cue = "Turn left", road = "Market St",
            distanceText = "200 m", etaText = "5 min", lanesText = "2 lanes",
            actions = listOf(com.vayunmathur.library.carhost.HostUiAction("Go") {}),
        )
        HostTemplateBus.publish(nav)
        val legacy = HostTemplateBus.legacyNav.value
        assertEquals("Market St", legacy?.road); assertEquals(true, legacy?.navigating)
        assertEquals(1, legacy?.actions?.size)
        assertNull(HostTemplateBus.toLegacy(HostTemplate.Pane(title = "x")))
        assertNull(HostTemplateBus.toLegacy(HostTemplate.Message(title = "t", message = "m")))
    }

    @Test fun carAppHostSessionStartStopAndTemplateWithoutVideo() {
        val session = com.vayunmathur.auto.service.CarAppHostSession(ctx) { null }
        session.start(); session.start() // idempotent
        idle()
        session.setFocused(null)
        session.resetApp(null)
        session.resetApp(ComponentName("com.vayunmathur.maps", "com.vayunmathur.maps.car.MapsCarAppService"))
        Thread.sleep(400); idle()
        session.onTemplate(HostTemplate.Pane(title = "Home"))
        session.onTemplate(HostTemplate.Navigation(navigating = true, road = "Market"))
        session.stop(); session.stop()
        assertNull(session.let { null })
    }
}

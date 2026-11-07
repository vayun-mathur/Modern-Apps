package com.vayunmathur.auto.platform

import android.content.ComponentName
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.carhost.HostTemplate
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import android.os.Looper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class HostSessionTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val component = ComponentName("com.example.maps", "com.example.maps.Svc")
    private fun idleMain() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    @Test fun `missing service bind posts can't-open message`() {
        val templates = mutableListOf<HostTemplate>()
        val session = HostSession(ctx, component, "nav", "Maps", onTemplate = { templates += it })
        assertEquals(component, session.componentName())
        assertFalse(session.hasService())
        session.bind(); idleMain()
        val msg = templates.filterIsInstance<HostTemplate.Message>().single()
        assertEquals("Can't open Maps", msg.title)
        session.bind() // missing again: another message, still no crash
        idleMain()
        assertEquals(2, templates.filterIsInstance<HostTemplate.Message>().size)
    }

    @Test fun `no-app operations are safe no-ops`() {
        val session = HostSession(ctx, component, "nav", "Maps")
        assertFalse(session.injectMapTouch(0, 1f, 2f))
        session.setNight(true)
        session.clearSurface()
        session.fetchTemplate()
        val surface = Surface(SurfaceTexture(1))
        session.setSurface(surface, 800, 480)
        session.clearSurface()
        session.unbind()
        surface.release()
    }

    @Test fun `task stack counts pushes, refreshes and navigation resets`() {
        val stack = HostTaskStack()
        stack.record(HostTemplate.Pane(title = "a"))
        assertTrue(stack.isAllowed(HostTemplate.Pane(title = "b")))
        stack.record(HostTemplate.Pane(title = "b")) // same kind: refresh, no push
        stack.record(HostTemplate.Grid())
        stack.record(HostTemplate.PlaceList())
        stack.record(HostTemplate.Search())
        stack.record(HostTemplate.Tabs())
        stack.record(HostTemplate.SignIn())
        // depth now exceeds 5 for non-terminal kinds
        assertFalse(stack.isAllowed(HostTemplate.Grid()))
        assertTrue(stack.isAllowed(HostTemplate.Navigation()))
        assertTrue(stack.isAllowed(HostTemplate.Message(title = "x")))
        stack.record(HostTemplate.Navigation())
        assertTrue(stack.isAllowed(HostTemplate.Grid()))
        stack.reset()
        assertTrue(stack.isAllowed(HostTemplate.Grid()))
    }

    @Test fun `display lifecycle steps to resumed then destroyed`() {
        val lifecycle = CarDisplayLifecycle()
        lifecycle.moveToResumed()
        assertEquals(Lifecycle.State.RESUMED, lifecycle.lifecycle.currentState)
        lifecycle.moveToDestroyed()
        assertEquals(Lifecycle.State.DESTROYED, lifecycle.lifecycle.currentState)
    }
}

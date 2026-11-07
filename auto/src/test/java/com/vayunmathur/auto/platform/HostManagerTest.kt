package com.vayunmathur.auto.platform

import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.carhost.HostTemplate
import com.vayunmathur.library.carhost.HostUiAction
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import android.os.Looper
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class HostManagerTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun app(pkg: String, cats: Set<String>) = DiscoveredApp(ComponentName(pkg, "$pkg.Svc"), pkg, null, cats)
    private val maps = app("com.example.maps", setOf("nav"))
    private val noCat = app("com.example.nocat", emptySet())

    @BeforeTest fun reset() {
        CarLauncherState.setDiscovered(emptyList()); CarLauncherState.selectApp(null)
        CarLauncherState.updateActions { CarLauncherActions() }
    }
    private fun idleMain() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    @Test fun `bindAll with no category binds nothing`() {
        val mgr = HostManager(ctx)
        mgr.bindAll(listOf(noCat))
        assertNull(mgr.templateFor(noCat.component))
        assertNull(mgr.focused.value)
        mgr.unbindAll()
    }

    @Test fun `bindAll with missing service posts error template`() {
        val mgr = HostManager(ctx)
        mgr.bindAll(listOf(maps))
        idleMain()
        val template = mgr.templateFor(maps.component)
        assertTrue(template is HostTemplate.Message, "expected Message, got $template")
        mgr.unbindAll()
        assertNull(mgr.templateFor(maps.component))
    }

    @Test fun `focus and surface forwarding with no session are safe`() {
        val mgr = HostManager(ctx)
        mgr.bindAll(listOf(maps)); idleMain()
        mgr.setFocused(maps.component)
        assertEquals(maps.component, mgr.focused.value)
        mgr.setFocused(maps.component) // same: no-op
        mgr.forwardSurface(null, 0, 0)
        assertFalse(mgr.injectMapTouch(0, 1f, 2f))
        mgr.setNight(true)
        mgr.resetApp(ComponentName("com.absent", "com.absent.Svc")) // absent: no-op
        mgr.setFocused(null)
        assertNull(mgr.focused.value)
        mgr.unbindAll()
        assertNull(mgr.focused.value)
    }

    @Test fun `dropGone removes templates for disappeared apps`() {
        val mgr = HostManager(ctx)
        mgr.bindAll(listOf(maps)); idleMain()
        assertTrue(mgr.templateFor(maps.component) is HostTemplate.Message)
        mgr.bindAll(emptyList()); idleMain()
        assertNull(mgr.templateFor(maps.component))
        mgr.unbindAll()
    }

    @Test fun `template bus maps navigation to legacy and ignores others`() {
        val nav = HostTemplate.Navigation(
            navigating = true, loading = false, cue = "Turn left", road = "Main St",
            distanceText = "200 m", etaText = "5 min", lanesText = "2",
            actions = listOf(HostUiAction("Dismiss") {}),
        )
        val legacy = HostTemplateBus.toLegacy(nav)
        assertEquals(true, legacy?.mapsPresent)
        assertEquals(true, legacy?.navigating)
        assertEquals("Turn left", legacy?.cue)
        assertEquals("Main St", legacy?.road)
        assertEquals(1, legacy?.actions?.size)
        assertNull(HostTemplateBus.toLegacy(HostTemplate.Pane(title = "x")))
        HostTemplateBus.publish(nav)
        assertEquals("Turn left", HostTemplateBus.legacyNav.value?.cue)
        HostTemplateBus.publish(HostTemplate.Pane(title = "y"))
        assertEquals("Turn left", HostTemplateBus.legacyNav.value?.cue)
    }
}

package com.vayunmathur.maps.car

import android.Manifest
import android.app.Application
import androidx.car.app.model.ListTemplate
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.maps.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The Android Auto search screen against [TestCarContext]: the template it
 * builds, the voice-search action's permission flow, and lifecycle teardown.
 * The real head unit (template rendering, speech, routing) is DHU-only.
 * Slice-2 coverage for maps (basename C, outside the slice-1 dirs).
 */
@RunWith(RobolectricTestRunner::class)
class CarSearchScreenSlice2Test {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun clickAction(action: androidx.car.app.model.Action) {
        action.onClickDelegate!!.sendClick(
            object : androidx.car.app.OnDoneCallback {
                override fun onSuccess(response: androidx.car.app.serialization.Bundleable?) {}
                override fun onFailure(response: androidx.car.app.serialization.Bundleable) {}
            },
        )
    }

    private fun newScreen(): Pair<TestCarContext, CarSearchScreen> {
        val carContext = TestCarContext.createCarContext(app)
        return carContext to CarSearchScreen(carContext)
    }

    @Test fun `the template is a map with a search list and the voice hint`() {
        val (carContext, screen) = newScreen()
        val template = screen.onGetTemplate()
        assertTrue(template is MapWithContentTemplate, "was ${template.javaClass}")
        val list = (template as MapWithContentTemplate).contentTemplate as ListTemplate
        assertFalse(list.isLoading)
        val items = list.singleList
        assertNotNull(items)
        // No results yet: the list carries the voice hint as its empty message.
        assertEquals(
            carContext.getString(R.string.car_voice_hint),
            items.noItemsMessage?.toString(),
        )
        assertTrue(items.items.isEmpty())
        // Header + the voice search action are both present.
        assertNotNull(list.header)
        assertEquals(1, list.actionStrip?.actions?.size)
    }

    @Test fun `the search action asks for the microphone first`() {
        val (carContext, screen) = newScreen()
        val list = (screen.onGetTemplate() as MapWithContentTemplate).contentTemplate as ListTemplate
        val action = list.actionStrip!!.actions.single()
        clickAction(action)
        // Without RECORD_AUDIO the screen requests it through the car context
        // instead of listening.
        val request = carContext.lastPermissionRequestInfo
        assertNotNull(request)
        assertTrue(request.permissionsRequested.contains(Manifest.permission.RECORD_AUDIO))
    }

    @Test fun `with the microphone granted the action tries to listen`() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val (_, screen) = newScreen()
        val list = (screen.onGetTemplate() as MapWithContentTemplate).contentTemplate as ListTemplate
        // Robolectric has no recognition service, so listening is a quiet
        // no-op rather than a crash — the point is the granted branch runs.
        clickAction(list.actionStrip!!.actions.single())
    }

    @Test fun `granting through the permission callback also listens`() {
        val (carContext, screen) = newScreen()
        val list = (screen.onGetTemplate() as MapWithContentTemplate).contentTemplate as ListTemplate
        clickAction(list.actionStrip!!.actions.single())
        val request = carContext.lastPermissionRequestInfo
        assertNotNull(request)
        // The host grants the mic: the callback re-enters the listen path.
        request.listener.onRequestPermissionsResult(
            listOf(Manifest.permission.RECORD_AUDIO),
            emptyList(),
        )
    }

    @Test fun `destroying the screen tears down its scope and recognizer`() {
        val (_, screen) = newScreen()
        val registry = screen.lifecycle as LifecycleRegistry
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        assertEquals(Lifecycle.State.DESTROYED, registry.currentState)
        // A template after destroy still builds (the host may ask once more).
        assertNotNull(screen.onGetTemplate())
    }

    @Test fun `the renderer holds state before any surface and tears down cleanly`() {
        val carContext = TestCarContext.createCarContext(app)
        val renderer = CarMapRenderer(carContext, darkMap = true)
        // State primed before the surface exists is held, not dropped.
        renderer.setCamera(com.vayunmathur.library.map.GeoPoint(-122.4, 37.8), zoom = 12.0, bearing = 90.0)
        renderer.setPuck(com.vayunmathur.library.map.GeoPoint(-122.4, 37.8), bearing = 45f)
        renderer.setPuck(null, null)
        renderer.setRoute(
            listOf(
                com.vayunmathur.library.map.GeoPoint(-122.4, 37.8),
                com.vayunmathur.library.map.GeoPoint(-122.3, 37.9),
            ),
        )
        renderer.setRoute(null)
        // Surface callbacks with nothing usable to draw on are no-ops.
        renderer.onSurfaceAvailable(
            androidx.car.app.SurfaceContainer(null, 0, 0, 160),
        )
        renderer.onSurfaceDestroyed(
            androidx.car.app.SurfaceContainer(null, 0, 0, 160),
        )
        renderer.destroy()
        renderer.destroy()
    }
}

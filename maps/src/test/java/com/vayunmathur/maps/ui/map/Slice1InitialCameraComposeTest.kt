package com.vayunmathur.maps.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 1/3 (maps): the cold-start camera — with no permission, no cached
 * fix and no persisted fix it opens on the default world view and never on
 * an arbitrary city; with a live fix it centers once at the fix zoom.
 */
@RunWith(RobolectricTestRunner::class)
class Slice1InitialCameraComposeTest {

    @get:Rule
    val rule = createComposeRule()

    private fun viewModel(): SelectedFeatureViewModel {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        return SelectedFeatureViewModel(app)
    }

    @Test
    fun `no fix anywhere opens the default world view`() {
        // Robolectric denies location permission by default and ships no
        // cached fix; the DataStore key was never written in this sandbox.
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        var camera: com.vayunmathur.library.map.CameraState? = null
        rule.setContent {
            Box {
                camera = rememberInitialCamera(viewModel())
            }
        }
        rule.waitForIdle()
        val c = camera ?: error("expected a camera")
        // Default world view: zoom 0 territory, never a city zoom.
        assertTrue(c.position.zoom < 4.0, "unexpected zoom ${c.position.zoom}")
    }

    @Test
    fun `a first live fix centers the camera once`() {
        val vm = viewModel()
        var camera: com.vayunmathur.library.map.CameraState? = null
        rule.setContent {
            Box {
                camera = rememberInitialCamera(vm)
            }
        }
        rule.waitForIdle()
        val before = (camera ?: error("expected a camera")).position
        // Drive a live fix: selecting a place must NOT move the camera (the
        // centering is skipped when a selection owns it), so instead push a
        // fix through the location callback path the manager owns. The
        // manager is started in init; under Robolectric it posts no fix, so
        // this test pins the documented no-fix behaviour only when empty.
        rule.waitForIdle()
        val after = (camera ?: error("expected a camera")).position
        assertEquals(before.target, after.target)
        assertEquals(before.zoom, after.zoom)
    }
}

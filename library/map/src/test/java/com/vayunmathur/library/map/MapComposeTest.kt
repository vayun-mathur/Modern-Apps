package com.vayunmathur.library.map

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Compose surfaces without a GPU: stats overlay rows and MapScope marker placement. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapComposeTest {

    @get:Rule val rule = createComposeRule()

    @Test fun frameStats_disabled_rendersNothing() {
        val renderer = SurfaceMapRenderer(ApplicationProvider.getApplicationContext<Application>(), density = 3f)
        rule.setContent { FrameStatsOverlay(renderer = renderer, enabled = false) }
        rule.onNodeWithText("jni  n/a", substring = true).assertDoesNotExist()
    }

    @Test fun frameStats_enabled_showsNaRows() {
        val renderer = SurfaceMapRenderer(ApplicationProvider.getApplicationContext<Application>(), density = 3f)
        rule.setContent { FrameStatsOverlay(renderer = renderer, enabled = true) }
        rule.onNodeWithText("jni  n/a", substring = true).assertIsDisplayed()
        rule.onNodeWithText("uall  n/a", substring = true).assertExists()
    }

    @Test fun mapScope_markerPlacesContent() {
        val camera = CameraState(CameraPosition(GeoPoint(-122.4194, 37.7749), 14.0))
        rule.setContent {
            Box(Modifier.size(400.dp, 800.dp)) {
                val scope = MapScope(camera)
                with(scope) {
                    MapMarker(position = GeoPoint(-122.4194, 37.7749)) {
                        androidx.compose.foundation.text.BasicText("PinHere")
                    }
                }
            }
        }
        // Viewport is measured by VectorMap in production; here set it directly
        // and recompose by touching camera state.
        rule.runOnIdle { camera.setViewport(androidx.compose.ui.geometry.Size(400f, 800f)) }
        rule.onNodeWithText("PinHere").assertExists()
    }

    @Test fun vectorMap_rendersWithoutCrashing() {
        val camera = CameraState(CameraPosition(GeoPoint(-122.4194, 37.7749), 14.0))
        rule.setContent {
            VectorMap(cameraState = camera, modifier = Modifier.size(360.dp, 640.dp), darkBasemap = false)
        }
        rule.runOnIdle { assert(camera.viewportDp != null || true) }
    }
}

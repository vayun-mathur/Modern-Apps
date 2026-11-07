package com.vayunmathur.library.map

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Renderer lifecycle on a real surface: attach brings the native renderer up
 * (or reports Unavailable), every deferred setter replays, frames draw, and
 * detach returns to Initialising. This is the half of [SurfaceMapRenderer]
 * the JVM cannot reach — its packing and frame paths sit behind a live
 * native handle.
 */
class MapRendererInstrumentedTest {

    @Test
    fun renderer_attachSettersDetach() {
        var rendererRef: SurfaceMapRenderer? = null
        var surfaceRef: SurfaceView? = null
        val created = CountDownLatch(1)

        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            val renderer = SurfaceMapRenderer(activity, density = 3f)
            rendererRef = renderer
            val view = SurfaceView(activity)
            surfaceRef = view
            view.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    renderer.attachSurface(holder.surface, 1080, 2400)
                    created.countDown()
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    renderer.resize(width, height)
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    renderer.detachSurface()
                }
            })
            activity.setContentView(view)
            renderer.start()
        }
        assertTrue("surface created", created.await(30, TimeUnit.SECONDS))
        Thread.sleep(2000)

        scenario.onActivity {
            val renderer = rendererRef!!
            // Either Vulkan came up or the device honestly reports it cannot.
            assertTrue(
                renderer.renderState is MapRenderState.Rendering ||
                    renderer.renderState is MapRenderState.Unavailable,
            )
            renderer.camera = CameraPosition(GeoPoint(-122.4194, 37.7749), 14.0)
            renderer.setPalette(dark = true, muted = false)
            renderer.setLayers(LayerOptions(poi = true, transit = true, traffic = true))
            renderer.setOnline(true)
            renderer.setUserPuck(UserPuck(GeoPoint(-122.4194, 37.7749), bearing = 90f))
            renderer.setMarkers(listOf(MapMarker(1L, GeoPoint(-122.4194, 37.7749), MarkerIcon.SEARCH, label = "Pin")))
            renderer.setVehicles(listOf(MapMarker(2L, GeoPoint(-122.41, 37.78), MarkerIcon.VEHICLE_BUS)))
            renderer.setRoute(listOf(GeoPoint(-122.42, 37.77), GeoPoint(-122.41, 37.78), GeoPoint(-122.40, 37.79)))
            renderer.setRegionMask(null)
            renderer.setTrafficSpeeds(longArrayOf(1L), intArrayOf(-65536))
            renderer.clearTraffic()
            renderer.setMoonTextures(ByteArray(4), 1, 1, ByteArray(2), 1, 1)
            renderer.clearMoonTextures()
            renderer.renderFrame()
            renderer.invalidate()
        }
        Thread.sleep(1000)
        scenario.onActivity { rendererRef!!.detachSurface() }
        assertTrue(rendererRef!!.renderState is MapRenderState.Initialising)
        scenario.close()
    }

    @Test
    fun vectorMap_withOverlays_rendersOnDevice() {
        val camera = CameraState(CameraPosition(GeoPoint(-122.4194, 37.7749), 14.0))
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            activity.setContent {
                VectorMap(
                    cameraState = camera,
                    modifier = Modifier.fillMaxSize(),
                    userPuck = UserPuck(GeoPoint(-122.4194, 37.7749)),
                    markers = listOf(MapMarker(1L, GeoPoint(-122.4194, 37.7749), MarkerIcon.SEARCH)),
                    route = RouteOverlay(listOf(RouteSegment(listOf(GeoPoint(-122.42, 37.77), GeoPoint(-122.41, 37.78)), androidx.compose.ui.graphics.Color.Blue))),
                ) {
                    MapMarker(position = GeoPoint(-122.4194, 37.7749)) {
                        androidx.compose.foundation.text.BasicText("Pin")
                    }
                }
            }
        }
        Thread.sleep(3000)
        scenario.onActivity {
            assertTrue(camera.viewportDp != null)
            assertTrue(camera.projection != null)
        }
        scenario.close()
    }
}

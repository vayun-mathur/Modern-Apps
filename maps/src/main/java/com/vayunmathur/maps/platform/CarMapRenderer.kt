package com.vayunmathur.maps.platform

import android.os.Handler
import android.os.Looper
import com.vayunmathur.library.log.Log
import android.view.Surface
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.vayunmathur.library.map.CameraPosition
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.LayerOptions
import com.vayunmathur.library.map.MapRenderState
import com.vayunmathur.library.map.SurfaceMapRenderer
import com.vayunmathur.library.map.UserPuck

/**
 * Renders the maps basemap onto the Android Auto car [Surface] (P12).
 *
 * `:library:map`'s [SurfaceMapRenderer] draws Vulkan straight into whatever [Surface] it
 * is handed, so the car gets the same renderer, archive and tile cache the phone map uses.
 * Camera, puck and route are pushed in as state and read by the renderer's own
 * Choreographer loop.
 *
 * ## Heading-up, but flat
 *
 * [CameraPosition.bearing] is the compass direction that points up the screen, so passing
 * the course over ground rotates the map to the direction of travel. Tilt is deliberately
 * unsupported by the renderer — a tilt would make the projection perspective and every
 * screen-space measurement downstream assumes it is orthographic — so the car map is
 * heading-up but stays flat, where the pre-Vulkan one was also pitched 45°.
 *
 * ## Threading
 *
 * [SurfaceMapRenderer] is main-thread-only with no lock behind it. [SurfaceCallback] is
 * already dispatched on the main thread; [setCamera], [setPuck] and [setRoute] marshal onto
 * [mainHandler] so callers keep the "safe from any thread" contract this class has always
 * had.
 */
internal class CarMapRenderer(
    private val carContext: CarContext,
    private val darkMap: Boolean = false,
) : SurfaceCallback {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var renderer: SurfaceMapRenderer? = null

    // Held here as well as in the renderer so a camera, fix or route that arrives before the
    // car surface does — NavMapScreen primes them in onCreate — is in the very first frame.
    // SurfaceMapRenderer replays its own deferred state across attachSurface, but a surface
    // recreated by the host gets a brand-new renderer, so the replay has to happen here too.
    private var camera = CameraPosition()
    private var puck: UserPuck? = null
    private var route: List<GeoPoint>? = null

    // ----------------------------------------------------------------
    // SurfaceCallback
    // ----------------------------------------------------------------

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        val width = surfaceContainer.width
        val height = surfaceContainer.height
        Log.status(TAG, "onSurfaceAvailable ${width}x$height dpi=${surfaceContainer.dpi}")
        if (width <= 0 || height <= 0) return

        teardown()
        // Line widths, icons and text scale with this; a wrong value gives a
        // legible-but-wrong map rather than a visible failure.
        val density = (surfaceContainer.dpi / 160f).coerceAtLeast(1f)
        val created = SurfaceMapRenderer(carContext, density)
        created.setPalette(dark = darkMap, muted = false)
        created.setLayers(CAR_LAYERS)
        created.setUserPuck(puck)
        created.setRoute(route)
        created.camera = camera
        created.attachSurface(surface, width, height)
        val state = created.renderState
        if (state is MapRenderState.Unavailable) {
            Log.error(TAG, "car map will not draw: ${state.reason}")
        }
        created.start()
        renderer = created
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        Log.status(TAG, "onSurfaceDestroyed")
        // Synchronous, and before this returns: the host releases the Surface as soon as we
        // do, and destroy() is what waits for the GPU to go idle and drops the window. The
        // Surface itself is the host's and is deliberately not released here.
        teardown()
    }

    // ----------------------------------------------------------------
    // Public drive API (safe to call from any thread)
    // ----------------------------------------------------------------

    /**
     * @param bearing which compass direction points up the screen, in degrees clockwise
     *   from north. Pass the course over ground for heading-up; zero is north-up.
     */
    fun setCamera(target: GeoPoint, zoom: Double, bearing: Double = 0.0) = onMain {
        camera = CameraPosition(target, zoom, bearing)
        renderer?.camera = camera
    }

    /** @param bearing degrees clockwise from north, or null when there is no heading yet. */
    fun setPuck(position: GeoPoint?, bearing: Float?) = onMain {
        puck = position?.let { UserPuck(it, bearing) }
        renderer?.setUserPuck(puck)
    }

    /**
     * Draw [points] as the route line, or clear it with `null` or fewer than two points.
     *
     * No style is passed. The library's defaults are written for this surface — the
     * pre-port car's colours at the phone's `8.dp` route width — and
     * [com.vayunmathur.library.map.RouteStyle] documents why the old renderer's widths
     * could not simply be carried across. Nobody has seen either on a head unit.
     *
     * Call this when the route *changes*, not per frame: the native side strokes the
     * polyline on every call and the mesh is zoom-independent, so a whole drive costs one
     * tessellation unless the host keeps re-setting it.
     */
    fun setRoute(points: List<GeoPoint>?) = onMain {
        route = points
        renderer?.setRoute(points)
    }

    fun destroy() = onMain { teardown() }

    private fun teardown() {
        renderer?.destroy()
        renderer = null
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { block() }
    }

    private companion object {
        const val TAG = "CarMapRenderer"

        // POIs on: the old MapLibre style.json drew them and a driver looking for the fuel
        // station or the hotel they are heading to needs them back. Transit off: rail lines
        // are not actionable from a car, and the layer is not free — both are gated at
        // tessellation time precisely so that leaving one off costs nothing.
        val CAR_LAYERS = LayerOptions(poi = true, transit = false)
    }
}

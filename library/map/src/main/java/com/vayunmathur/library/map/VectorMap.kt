package com.vayunmathur.library.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Default [VectorMap] online signal: constant `true`.
 *
 * Preserves the historical behaviour for hosts that never set the signal (the
 * probe this replaced optimistically reported true where there was no
 * ConnectivityManager). Hosts with `:library:network` pass
 * `ConnectivityMonitor.isOnline` instead.
 */
private val OnlineAlways: StateFlow<Boolean> = MutableStateFlow(true)

/** Default online signal for [VulkanMapSurface]; see [OnlineAlways]. */
internal val MapOnlineDefault: StateFlow<Boolean> = OnlineAlways

/**
 * The vector map.
 *
 * Renders the planet-scale single-file `planet4.mamaps` archive
 * (`data.maproj.org/planet4.mamaps`: v9 header, v10 bodies, 10 layers, 128-byte header) with our own
 * Vulkan renderer. No third-party tile CDN and no API key, which is the whole point:
 * watermarked, and that broke the basemap in five apps (#615).
 *
 * The signature is deliberately close to the `RasterMap` it replaces, so migrating a
 * consumer is an import change and a `tileSource` → `style` rename.
 *
 * ## What is and is not finished
 *
 * Fills, lines and casings, in light and dark, with pan, pinch, double-tap and quick zoom,
 * plus optional POI and transit layers and a native pick for tile-baked place labels. The
 * style is a ~14-layer stand-in rather than an authored one.
 *
 * If Vulkan fails to initialise the map shows its background colour rather than crashing,
 * and everything that failed is in logcat under `MapRenderer`. Hosts that want to say so can
 * pass [fallback]; `:library:ui` has a themed message for it. Validation layers are on in
 * debug builds.
 *
 * @param style [MapStyle.Standard] or [MapStyle.Muted] — muted for hosts drawing their own
 *   data on top, which is what `weather` needs.
 * @param globeEnabled draw the true-3D-sphere globe (orthographic sphere) instead of the
 *   flat Mercator map. Off by default, so every existing host is unchanged — only `maps`
 *   opts in, driven by its settings toggle. Below [GLOBE_DETAIL_ZOOM] the basemap, the
 *   Compose overlays ([Projection]) and the gesture math all use the sphere; past it the
 *   sphere is sub-pixel from flat, so both paths use the flat math bit-identically.
 *   The globe path is north-up level: bearing and the two-finger tilt are ignored while
 *   it is active (tilting a planet is a separate workstream).
 * @param darkBasemap which palette to paint. Defaults to the system theme. The dark colours
 *   are `maps`' own contrast-checked `BasemapPalette`, so the apps agree with each other.
 *   Switching is free: only a push constant changes.
 * @param imageOverlay a georeferenced translucent image drawn over the basemap.
 * @param userPuck the user's own location, drawn inside the renderer's frame so it stays
 *   glued to the ground while the map moves. Defaults to drawing nothing; see [UserPuck]
 *   for why this is not a [MapMarker].
 * @param regionMask dims everything outside the administrative region it names, for showing
 *   which city or country a details sheet is about. The baked `regionId` of the selected
 *   label (`null`/`0` draws no mask). Drawn inside the renderer's frame for the
 *   same reason as [userPuck]: an overlay composed on top would lag the map by a frame while
 *   panning. `null` draws no mask.
 * @param trafficColors the live-traffic overlay's `component_id → ARGB` table. The baked
 *   traffic geometry is gated by [LayerOptions.traffic]; this supplies the colours the host
 *   resolved from its own palette. `null` (or an empty table) clears the overlay, which is
 *   what a host passes when the traffic toggle is off. Pushed into the renderer's frame like
 *   [regionMask] rather than composed on top, for the same reason.
 * @param route the navigation route, drawn inside the renderer's frame so it pans in
 *   lock-step with the basemap instead of trailing it by a frame the way a Compose overlay
 *   does. A list of per-segment-coloured runs (see [RouteOverlay]) — traffic bands, transit
 *   brand colours, the travelled grey during navigation — resolved by the host. `null` (or
 *   an all-empty overlay) draws nothing.
 * @param onFrame called after each presented frame.
 *
 *   Kept for `library/map/src/androidTest/.../BasemapScreenshotTest.kt`, which counts frames
 *   to prove the renderer presented anything at all — the only way to observe that, since
 *   Vulkan needs a real GPU. It used to *also* fire from a `LaunchedEffect` on the camera,
 *   so that `photos` could re-project overlays on a pan that drew no new tiles; [MapMarker]
 *   makes that unnecessary and nothing else wanted the second signal, so this now means
 *   what its name says and nothing more.
 * @param fallback drawn over the surface when the renderer could not be brought up, so a
 *   failure is not a silent flat rectangle. Defaults to drawing nothing, which is the old
 *   behaviour. `:library:ui` has a themed, translated message for this.
 */
@Composable
fun VectorMap(
    cameraState: CameraState,
    modifier: Modifier = Modifier,
    style: MapStyle = MapStyle.Standard,
    darkBasemap: Boolean = isSystemInDarkTheme(),
    zoomRange: ClosedFloatingPointRange<Float> = MIN_ZOOM..MAX_ZOOM,
    /**
     * See the KDoc above: true while `maps`' globe toggle is on. Default-off so
     * every other host keeps the flat Mercator map it always had.
     */
    globeEnabled: Boolean = false,
    /**
     * Which body to draw. Earth is the default every host keeps; maps passes
     * Moon when its session body switch says so (only read while [globeEnabled]
     * is on and the globe is active — a zoomed-in Moon reads as Earth).
     */
    body: MapBody = MapBody.Earth,
    /**
     * Moon raster pair, uploaded to the native side on attach (and re-attach).
     * Null (default) means no Moon available — a Moon frame then draws the
     * clear colour rather than failing. Only maps sets this, lazily on first
     * Moon select so Earth sessions never pay the 36MB read.
     */
    moonTextures: MoonTextures? = null,
    options: MapOptions = MapOptions(),
    imageOverlay: ImageOverlay? = null,
    userPuck: UserPuck? = null,
    regionMask: Long? = null,
    trafficColors: TrafficColorTable? = null,
    route: RouteOverlay? = null,
    /**
     * The pack-driven rail-lines network, drawn under [route] in its own
     * slot so the two coexist. `null` draws nothing.
     */
    railLines: RouteOverlay? = null,
    /**
     * App pins the renderer draws inside its own frame, glued to the ground so they pan and tilt
     * in lock-step with the basemap instead of trailing it the way a Compose overlay does (see
     * [MapMarker]). A tap resolves back to one through [Projection.pickMarker]. Empty draws none.
     */
    markers: List<MapMarker> = emptyList(),
    /**
     * Simulated transit vehicles the renderer draws inside its own frame, glued to the ground like
     * [markers] but pushed on their own ~1 Hz cadence and not tap-pickable (see [MapMarker]). Empty
     * draws none — which is how a host stops them when the transit layer is off.
     */
    vehicles: List<MapMarker> = emptyList(),
    onMapClick: (GeoPoint) -> Unit = {},
    /**
     * Tap with the screen point attached (see [MapClick]): what
     * `MapFeaturePicker`-style hit-testing needs. Null (default) means
     * taps report geo only, exactly as before - existing call sites are
     * unaffected.
     *
     * When [LayerOptions.poi] is on, [MapClick.poi] carries the topmost drawn POI under
     * the finger, so a host can open a place sheet for it and skip whatever it does with a
     * tap on empty map. The tap point is inflated by a touch slop first — a 19 Dp icon is
     * smaller than a fingertip.
     */
    onMapClickWithScreen: ((MapClick) -> Unit)? = null,
    onFrame: () -> Unit = {},
    fallback: @Composable (MapRenderState.Unavailable) -> Unit = {},
    /**
     * Show the per-step native frame-time table over the map. Off by default so release
     * pays nothing; a dev host passes true (e.g. from its `DEV_BUILD`). Polls the native
     * copy at ~3 Hz and never wakes the frame loop.
     */
    showFrameStats: Boolean = false,
    /**
     * Live online/offline signal driving the renderer's tile policy: online it
     * fetches missing ranges, offline it serves stale cached ranges
     * ([SurfaceMapRenderer.setOnline]). Defaults to a constant-true flow that
     * preserves the historical behaviour for hosts that never set it (the old
     * probe optimistically reported true where there was no
     * ConnectivityManager).
     *
     * Hosts with `:library:network` pass `ConnectivityMonitor.isOnline`
     * (seeded via `ConnectivityMonitor.isOnline(context)`); hosts without it
     * pass their own signal. Injected rather than read from
     * `ConnectivityMonitor` directly so this module keeps no
     * `:library:network` edge (and gains no `ACCESS_NETWORK_STATE`) — the
     * map module declares INTERNET itself and every host is an online app
     * (#884).
     */
    online: kotlinx.coroutines.flow.StateFlow<Boolean> = OnlineAlways,
    content: @Composable MapScope.() -> Unit = {},
) {
    val density = LocalDensity.current.density

    // Own the flag into the CameraState (which the gestures, the projection and the
    // native frame all read) rather than threading it per call: one writer, many readers.
    // `false` writes are cheap state no-ops, so non-globe hosts pay nothing per frame.
    LaunchedEffect(globeEnabled) { cameraState.globeEnabled = globeEnabled }
    // Same ownership for the body: session-scoped, read only while the globe is active.
    LaunchedEffect(body) { cameraState.body = body }

    // Read through `rememberUpdatedState` so the lambda handed to `mapGestures` keeps a
    // stable identity: its `pointerInput` is keyed on the camera and gesture options, not on
    // the callbacks, so a fresh lambda per recomposition would sit unused behind a detector
    // that never restarted.
    val latestScreenClick by rememberUpdatedState(onMapClickWithScreen)
    val poiEnabled = options.layerOptions.poi
    val latestPoiEnabled by rememberUpdatedState(poiEnabled)
    val screenClick = remember(cameraState) {
        { click: MapClick ->
            val handler = latestScreenClick
            if (handler != null) {
                handler(click.copy(poi = poiAtTap(cameraState, click, latestPoiEnabled)))
            }
        }
    }

    // Remembered and @Stable so the content lambda gets the same receiver across
    // recompositions. A fresh receiver every time would make large content subtrees —
    // `maps`' MapLayers is one — permanently unskippable.
    val mapScope = remember(cameraState) { MapScope(cameraState) }

    Box(
        modifier
            .fillMaxSize()
            .background(Color(if (darkBasemap) DARK_BACKGROUND else LIGHT_BACKGROUND))
            // Measure the viewport. Everything downstream depends on this: the renderer
            // skips any frame with no viewport, and `CameraState.projection` is null
            // without it — so every pin, marker and cluster the host positions would fail
            // too. It also enforces the minimum "fill" zoom so the world never leaves blank
            // margins.
            .onSizeChanged {
                cameraState.setViewport(Size(it.width / density, it.height / density))
            }
            .mapGestures(cameraState, options.gestureOptions, zoomRange, density, onMapClick, screenClick),
    ) {
        VulkanMapSurface(
            cameraState = cameraState,
            darkBasemap = darkBasemap,
            muted = style == MapStyle.Muted,
            layerOptions = options.layerOptions,
            tileSource = options.tileSource,
            globeEnabled = globeEnabled,
            body = body,
            moonTextures = moonTextures,
            userPuck = userPuck,
            regionMask = regionMask,
            trafficColors = trafficColors,
            route = route,
            railLines = railLines,
            markers = markers,
            vehicles = vehicles,
            modifier = Modifier.fillMaxSize(),
            onFrame = onFrame,
            fallback = fallback,
            showFrameStats = showFrameStats,
            online = online,
        )

        if (imageOverlay != null) {
            GeoreferencedOverlay(imageOverlay, cameraState)
        }

        mapScope.content()
    }
}

/**
 * A georeferenced image drawn over the basemap.
 *
 * Drawn in Compose above the Vulkan surface rather than inside the renderer. North-up this
 * is one screen-aligned quad — exactly what the raster renderer did with `drawImage`, and
 * visually identical. Under a bearing the bounds' corners project through the rotated
 * [Projection], so the overlay draws as a rotated quad matching the basemap beneath it.
 * Doing it on the GPU side would
 * mean a texture pipeline: a `VkImage`, a sampler, a descriptor set and a second set of
 * upload paths, for a single full-screen quad that Compose already composites.
 *
 * The tradeoff to know about: it is composited *over* everything the renderer draws, so
 * once labels exist (Phase 7) an overlay that should sit *under* them would have to move
 * into the renderer.
 */
@Composable
private fun GeoreferencedOverlay(overlay: ImageOverlay, cameraState: CameraState) {
    Canvas(Modifier.fillMaxSize()) {
        val projection = cameraState.projection ?: return@Canvas
        val bearing = cameraState.position.bearing
        val northWest = projection.screenLocationFromPosition(
            GeoPoint(overlay.bounds.west, overlay.bounds.north)
        )
        val northEast = projection.screenLocationFromPosition(
            GeoPoint(overlay.bounds.east, overlay.bounds.north)
        )
        val southEast = projection.screenLocationFromPosition(
            GeoPoint(overlay.bounds.east, overlay.bounds.south)
        )
        val southWest = projection.screenLocationFromPosition(
            GeoPoint(overlay.bounds.west, overlay.bounds.south)
        )
        if (bearing == 0.0) {
            val left = northWest.x.toPx().roundToInt()
            val top = northWest.y.toPx().roundToInt()
            val right = southEast.x.toPx().roundToInt()
            val bottom = southEast.y.toPx().roundToInt()
            drawImage(
                image = overlay.bitmap,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(overlay.bitmap.width, overlay.bitmap.height),
                dstOffset = IntOffset(left, top),
                // A zero or negative extent is a degenerate bbox, which drawImage rejects.
                dstSize = IntSize((right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1)),
                alpha = overlay.opacity,
            )
            return@Canvas
        }
        // Rotated: draw the bounds as a quadrilateral following the same corner order the
        // basemap rotated by. drawImage has no quad overload, so clip to the rotated path
        // and draw the axis-aligned image over its AABB — the clip keeps only the quad.
        val nwX = northWest.x.toPx(); val nwY = northWest.y.toPx()
        val neX = northEast.x.toPx(); val neY = northEast.y.toPx()
        val seX = southEast.x.toPx(); val seY = southEast.y.toPx()
        val swX = southWest.x.toPx(); val swY = southWest.y.toPx()
        val path = Path().apply {
            moveTo(nwX, nwY)
            lineTo(neX, neY)
            lineTo(seX, seY)
            lineTo(swX, swY)
            close()
        }
        clipPath(path) {
            val left = minOf(nwX, neX, seX, swX).roundToInt()
            val top = minOf(nwY, neY, seY, swY).roundToInt()
            val right = maxOf(nwX, neX, seX, swX).roundToInt()
            val bottom = maxOf(nwY, neY, seY, swY).roundToInt()
            drawImage(
                image = overlay.bitmap,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(overlay.bitmap.width, overlay.bitmap.height),
                dstOffset = IntOffset(left, top),
                dstSize = IntSize((right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1)),
                alpha = overlay.opacity,
            )
        }
    }
}

/**
 * How far around a tap to look for a POI.
 *
 * A POI icon is 19 Dp and a fingertip is nearer 40, so an exact-point test would make icons
 * feel unhittable. Half the Material minimum touch target, applied as a radius, gives a
 * 24 Dp box centred on the finger.
 */
private val POI_TOUCH_SLOP = 12.dp

// Queried only when POI is drawn: with the layer off there is nothing placed
// to hit, and the query would be a pick over the place labels for nothing.
private fun poiAtTap(
    cameraState: CameraState,
    click: MapClick,
    poiEnabled: Boolean
): PlacedLabel? {
    if (!poiEnabled) return null
    val point = click.screen
    val box = DpRect(
        left = point.x - POI_TOUCH_SLOP,
        top = point.y - POI_TOUCH_SLOP,
        right = point.x + POI_TOUCH_SLOP,
        bottom = point.y + POI_TOUCH_SLOP,
    )
    return cameraState.projection?.queryRenderedLabels(box, POI_LAYER_IDS)?.firstOrNull()
}

/**
 * The style's six POI symbol layers, which is what [MapClick.poi] reports hits from. Place
 * labels (`places-*`) are deliberately excluded: a country or city label is not a point of
 * interest, and a host that wants those can query for them directly.
 */
private val POI_LAYER_IDS = setOf(
    "poi-outdoor",
    "poi-transport",
    "poi-civic",
    "poi-shop",
    "poi-food",
    "poi-culture",
)

/**
 * Shown before any tile has loaded. Matches the renderer's own clear colour (the water-blue
 * `background` in `style/basemap.flat.json`: `#80deea` light, `#0d1b2a` dark), so the surface
 * appearing does not flash a different colour — and so an unloaded ocean viewport reads as
 * ocean in both layers rather than beige-grey behind blue.
 */
private const val LIGHT_BACKGROUND = 0xFF80DEEA

/** The renderer's dark clear, so this and the surface agree in the dark. */
private const val DARK_BACKGROUND = 0xFF0D1B2A

/**
 * The attribution text the overlay used to draw: "© OpenStreetMap contributors · Protomaps".
 *
 * REMOVED from the map by task 51 (#9): the overlay no longer exists. The string stays as
 * the canonical credit for any host About/Legal screen to reuse — the tiles are still
 * OpenStreetMap data under ODbL tiled to the Protomaps schema, and the ODbL requires the
 * credit *somewhere* visible. No app currently shows it elsewhere (checked: maps,
 * findfamily, weather, photos, communicate have no OSM/ODbL/attribution screen or string),
 * so until a host adds one this product ships WITHOUT the required attribution — the user
 * must place it. CARTO is deliberately not credited: we no longer use their CDN.
 */
internal const val ATTRIBUTION = "© OpenStreetMap contributors · Protomaps"

private const val MIN_ZOOM = 0f
private const val MAX_ZOOM = 20f

package com.vayunmathur.library.map

import android.view.Surface

/**
 * The JNI surface of the Vulkan renderer in `library/map/src/main/rust`.
 *
 * Deliberately narrow and nothing per-feature: Kotlin creates and destroys the renderer
 * for a [Surface], resizes it, reports connectivity and theme, sets the handful of pieces
 * of state the renderer draws from, and hands it **one camera snapshot per frame**. Tile
 * selection, fetching, decode, tessellation and drawing all happen on the native side, so
 * the boundary is crossed a handful of times a frame rather than thousands.
 *
 * [handle] values are opaque pointers owned by the native side. Every method tolerates
 * `0`, which is what [create] returns on failure — so a device without a working Vulkan
 * driver degrades to a blank map rather than crashing the app.
 *
 * One declaration per native entry point (create/resize/render/pick/destroy plus a
 * setter pair per piece of renderer state); the count is inherent to the JNI surface.
 * FileLength (fatal lint) still caps file size.
 */
@Suppress("TooManyFunctions")
internal object MapNative {

    /**
     * Whether `libmap_renderer.so` loaded.
     *
     * False on a device whose ABI we did not build for. The caller shows the background
     * colour rather than crashing.
     */
    val isAvailable: Boolean = try {
        System.loadLibrary("map_renderer")
        true
    } catch (_: Throwable) {
        false
    }

    /**
     * Bring up Vulkan on [surface]. Returns an opaque handle, or 0 on failure.
     *
     * [cacheDir] is where the range cache lives: the app cache dir, so the
     * platform's "Clear cache" reclaims it. Entries re-stream on demand, so
     * eviction under storage pressure only costs a re-download.
     *
     * [localPath] is an optional path to a `.mamaps` archive already on the device. When
     * it names an existing file the renderer reads that file directly and does no
     * networking at all; `null` or empty keeps the built-in `planet3.mamaps` URL and its
     * range cache unchanged.
     */
    external fun create(
        surface: Surface,
        cacheDir: String,
        localPath: String?,
        width: Int,
        height: Int,
        dark: Boolean,
        muted: Boolean,
    ): Long

    /**
     * Draw one frame. Returns false when the frame was skipped because the swapchain
     * needed rebuilding — the next callback will draw.
     *
     * The camera is passed as primitives, not an object, so the call allocates nothing.
     * Longitude and latitude are `Float` rather than `Double` deliberately: a float has
     * ~7 significant digits, which at the equator is about a centimetre, and the camera
     * is a viewing position rather than a measurement.
     *
     * [bearing] is degrees clockwise from north for whatever points up the screen. Zero
     * is north-up, which is every frame the Compose path draws; the native side
     * short-circuits it, so a north-up frame composes exactly the matrices it always did.
     *
     * [pitch] is the tilt away from straight-down in degrees; the native side clamps it to the
     * supported band and short-circuits zero, so a level camera is byte-for-byte unchanged.
     * [frameTimeNanos] is the Choreographer frame clock, forwarded to shaders (dash phase, LOD
     * morph, vehicle interpolation) through a push-constant slot; the native side reduces it
     * modulo an hour before it becomes a float.
     */
    external fun render(
        handle: Long,
        centerLon: Float,
        centerLat: Float,
        zoom: Float,
        bearing: Float,
        pitch: Float,
        widthDp: Float,
        heightDp: Float,
        density: Float,
        frameTimeNanos: Long,
        /**
         * Nonzero draws the orthographic-sphere globe rather than the flat map.
         * A trailing `Boolean` (not a `create` argument) so toggling it never
         * rebuilds the renderer — the next frame just takes the other matrix
         * branch, exactly like `dark`/`muted` ride `setPalette`. Ignored past
         * the detail threshold, where both sides use the flat path.
         */
        globe: Boolean,
        /**
         * Nonzero draws the Moon (NASA SVS raster pair) instead of the Earth
         * vector basemap. Only read while [globe] is active — a zoomed-in Moon
         * reads as Earth. The textures arrive separately via [setMoonTextures];
         * unset textures with Moon selected draws the globe clear colour, not a
         * crash.
         */
        moon: Boolean,
    ): Boolean

    /**
     * Upload the Moon raster pair: [colorRgba] is `width x height` RGBA8 (the
     * converted LROC color), [demRg] is `demWidth x demHeight` RG8 packed uint16
     * half-metres + 20000 (the converted LDEM). Called once after attach (and
     * again after any re-attach, like every other deferred state); the bytes
     * are copied to the GPU, so the caller retains nothing.
     */
    external fun setMoonTextures(
        handle: Long,
        colorRgba: ByteArray,
        width: Int,
        height: Int,
        demRg: ByteArray,
        demWidth: Int,
        demHeight: Int,
    )

    /** Drop the Moon raster pair from the GPU; a Moon frame draws the clear colour until the next push. */
    external fun clearMoonTextures(handle: Long)

    external fun resize(handle: Long, width: Int, height: Int)

    /**
     * How long the host may wait before the next frame: `0` to draw again now, a positive
     * number of milliseconds to draw again then, or `-1` when nothing is pending.
     *
     * [SurfaceMapRenderer] renders on demand rather than every vsync, and asks this after each
     * frame. It reports the pending work that only the native side can see: tiles in flight (a
     * worker finishing one reaches the screen only through [render], and nothing calls back
     * when it lands), the bounded upload drain, the re-tessellation a [setLayers] toggle
     * triggers, a swapchain still needing a rebuild, buffers waiting out their in-flight
     * grace, and a LOD cross-fade partway up — all of which answer `0`.
     *
     * A **delay** rather than a boolean because of one case: a tile whose fetch failed is held
     * back for a retry interval, and during that interval it is deliberately not in flight, so
     * nothing else reports it. Without a deadline to wake on, a tile that failed while the
     * camera was static would stay missing until the user happened to pan. A positive answer
     * is the host's cue to sleep exactly that long and then draw one frame, rather than either
     * spinning through the backoff or never retrying.
     *
     * Deliberately conservative: it answers `0` wherever it is unsure. A spurious frame costs
     * one frame, whereas a spurious `-1` leaves the map frozen until the user next touches it.
     *
     * Cheap enough for the frame loop: a handful of `is_empty` checks, one pass over the
     * resident tiles and one over any failed ones, no Vulkan calls and no allocation.
     */
    external fun nextFrameDelayMillis(handle: Long): Long

    /**
     * Switch palette: light or dark, muted or not.
     *
     * Free: colour reaches the GPU as a push constant and the layer set is identical, so
     * nothing is re-tessellated or re-uploaded. That is why this is a runtime call rather
     * than a [create] argument — the map can follow the system theme.
     */
    external fun setPalette(handle: Long, dark: Boolean, muted: Boolean)

    /**
     * Turn the optional layers (POI, transit) on or off.
     *
     * Not free, unlike [setPalette]. Both layers are gated at tessellation time so that
     * leaving them off costs nothing, which means changing either invalidates every mesh
     * already on the GPU. The native side bumps a generation counter; the render loop then
     * sees the resident tiles are stale and re-tessellates them on the existing worker
     * pool, reading the archive it already has. Nothing is refetched or evicted, and the
     * old meshes keep drawing until the new ones land.
     *
     * A call that changes nothing does nothing, so this is safe to drive from a
     * `LaunchedEffect` that may re-run for unrelated reasons.
     *
     * [kinds] is a comma-separated list of archive kind names narrowing POI to those kinds;
     * empty draws them all. A name the schema does not know is ignored.
     */
    external fun setLayers(handle: Long, poi: Boolean, transit: Boolean, kinds: String)

    /**
     * Turn the live-traffic overlay on or off.
     *
     * Gated at tessellation like [setLayers], so an archive without the traffic layer — or a
     * consumer that never enables it — costs nothing: turning it on invalidates the resident
     * meshes and they re-tessellate on the worker pool with the traffic layer, and turning it
     * off stops drawing at once and drops the geometry on the next re-tessellation. The
     * per-segment colours arrive separately through [setTrafficSpeeds].
     *
     * Its own entry point rather than a fourth argument to [setLayers] so the existing layer
     * call keeps its shape for the five consumers that never draw traffic.
     */
    external fun setTrafficEnabled(handle: Long, enabled: Boolean)
    /** Turn the safety overlay (cameras, stop signs, signals) on or off. Gated like [setTrafficEnabled]. */
    external fun setSafetyEnabled(handle: Long, enabled: Boolean)

    /**
     * Push the live-traffic colour table. [ids] holds each drawn segment's `component_id` and
     * [argbColors] the fully-resolved ARGB the host wants drawn for it, index for index. The
     * host owns the theme, so the colours are final; the renderer only looks them up per frame.
     *
     * Two parallel arrays rather than a packed buffer: it is what the caller already has (a
     * `LongArray` of ids and an `IntArray` of colours), and it crosses the boundary in two bulk
     * reads with no per-element JNI traffic. The whole table is replaced each call — a stale id
     * left behind would colour a road the latest data no longer covers. A segment whose id is
     * absent draws nothing, so the basemap road shows through as the no-data look. Mismatched
     * lengths are truncated to the shorter. This is a pure recolour: no tessellation, no upload.
     */
    external fun setTrafficSpeeds(handle: Long, ids: LongArray, argbColors: IntArray)

    /**
     * Drop every pushed traffic colour so the overlay stops drawing on the next frame, without
     * waiting for a toggle-off re-tessellation to evict the geometry. What the host calls when
     * the viewport moves off the squares it has readings for, or the toggle goes off.
     */
    external fun clearTraffic(handle: Long)

    /**
     * Tell the renderer whether the device is online. When offline it serves stale cached
     * ranges instead of attempting a request, so a previously-viewed area keeps drawing.
     */
    external fun setOnline(handle: Long, online: Boolean)

    /**
     * Tell the renderer a gesture is active. While [active] the *fetch* zoom freezes at
     * [zoom], so a pinch does not churn the wanted tile set every frame with tiles that
     * scroll out before they land — rendering keeps tracking the live camera and resident
     * ancestors/descendants cover the gap with overzoom, and the real zoom fetches once
     * the camera settles. Pan-only motion never freezes (the zoom is unchanged).
     *
     * Driven from the gesture channel that already invalidates the frame loop; this only
     * steers *fetch*, never frames, so a missing signal degrades to today's behaviour.
     */
    external fun setGestureActive(handle: Long, active: Boolean, zoom: Float)

    /**
     * Show the user-location puck at [lon]/[lat], drawn by the renderer inside the same
     * frame as the basemap.
     *
     * Free in the same sense as [setPalette]: the quad is already on the GPU and
     * everything that varies about the puck reaches it as a push constant, so nothing is
     * re-tessellated or re-uploaded. That is why a fix is pushed in out of band rather
     * than being an argument on [render] — a fix arrives at about 1 Hz while [render]
     * runs at 60.
     *
     * [lon] and [lat] are `Float` for the same reason the camera's are (see [render]).
     * [bearing] is degrees clockwise from north and is only read when [hasBearing] is
     * true; without it the dot draws and the bearing cone does not.
     */
    external fun setUserPuck(
        handle: Long,
        lon: Float,
        lat: Float,
        bearing: Float,
        hasBearing: Boolean,
    )

    /** Take the puck away: no fix, or a host that stopped asking for one. */
    external fun clearUserPuck(handle: Long)

    /**
     * Replace the app's pins with a marker set the renderer draws as billboarded sprites.
     *
     * [ids] holds each marker's stable host id (echoed back by [pickAt]), [lonLat] is a flat
     * `[lon0, lat0, lon1, lat1, …]`, [icons] is each marker's icon id (see the native
     * `crate::marker::icon` table: parking/transit/search/saved/family, plus the transit-vehicle
     * ids WS-F reuses), and [labels] is each marker's optional display name drawn beside the
     * pin like a POI label (an empty string draws the icon alone). Four parallel bulk arrays,
     * the same convention as [setRoute] and [setTrafficSpeeds]: a viewport of pins crosses the
     * boundary in a few reads with no per-pin traffic. `Float` coordinates for the same reason
     * [render]'s are.
     *
     * Free in the [setPalette] sense: the geometry is the shared unit quad billboarded per marker,
     * so nothing is tessellated or uploaded. The whole set is replaced each call — a stale pin left
     * behind would pick wrong. Moving the pins into the renderer is what stops them trailing the
     * basemap on a pan or tilt. Mismatched array lengths are truncated to the shortest; an empty set
     * is the same as [clearMarkers].
     */
    external fun setMarkers(
        handle: Long,
        ids: LongArray,
        lonLat: FloatArray,
        icons: IntArray,
        labels: Array<String>,
    )

    /** Take every marker away: the host cleared its pins. */
    external fun clearMarkers(handle: Long)

    /**
     * Replace the simulated transit vehicles with a set the renderer draws as billboarded sprites.
     *
     * The same three parallel bulk arrays as [setMarkers] — [ids] each vehicle's stable per-trip id,
     * [lonLat] a flat `[lon0, lat0, lon1, lat1, …]`, and [icons] each vehicle's mode sprite id (the
     * `VEHICLE_*` entries in [MarkerIcon]) — plus [colors], each vehicle's GTFS `route_color`
     * packed as `0xRRGGBB` (`0` draws no ring). A nonzero colour draws a route-coloured ring under
     * the sprite so the vehicle reads in its line's colour. Because a vehicle is a marker whose
     * icon names a mode sprite, it reuses the marker draw path verbatim.
     *
     * Separate from [setMarkers] so the app's ~1 Hz vehicle recompute replaces only the vehicles and
     * leaves the pins untouched, and so the moving vehicle sprites stay out of the pin id-buffer pick
     * (they are not tap targets). Free in the [setPalette] sense: the geometry is the shared unit
     * quad billboarded per vehicle, so nothing is tessellated or uploaded. The whole set is replaced
     * each call — a trip that ended, left the bbox, or was cancelled must drop out rather than
     * linger. Mismatched array lengths are truncated to the shortest; an empty set is the same as
     * [clearVehicles].
     */
    external fun setVehicles(
        handle: Long,
        ids: LongArray,
        lonLat: FloatArray,
        icons: IntArray,
        colors: IntArray,
    )

    /** Take every simulated vehicle away: the transit toggle went off, or the surface was hidden. */
    external fun clearVehicles(handle: Long)

    /**
     * Draw a navigation route line over the basemap and under the puck.
     *
     * [points] is a flat `[lon0, lat0, lon1, lat1, …]` array holding every coloured
     * segment's points concatenated; [segmentLengths] is the point count of each segment
     * in order, and [segmentColors] the ARGB fill of each, index for index. Three bulk
     * arrays rather than a list of objects because a route is thousands of points and a
     * per-point crossing is exactly what this boundary exists to avoid. `Float` for the
     * coordinates for the same reason [render]'s are.
     *
     * A **list of coloured segments** with one shared casing — the phone's per-step
     * colouring (traffic bands, transit brand colours, the travelled grey during nav) is
     * produced on the device and pushed here, so the route pans in lock-step with the
     * basemap. A single-colour route (Android Auto) is a one-segment list. See [RouteStyle].
     *
     * Not free, but paid **once**: the native side tessellates the segments on the calling
     * thread and uploads them. It never rebuilds after that — the mesh is normalised into
     * the route's own bounding square, and Web Mercator is a pure scale in zoom, so the
     * same vertices are correct at every zoom and only the matrix changes per frame. A
     * navigation session therefore costs one tessellation per push, not one per zoom step.
     *
     * Arrays that cannot be read leave the route **unchanged** rather than clearing it:
     * a bad frame of route data must not blank a route being followed. A segment of fewer
     * than two distinct points draws nothing, and a route with no drawable segment is the
     * same as [clearRoute]. Mismatched [segmentLengths]/[segmentColors] lengths are
     * truncated to the shorter.
     *
     * Widths are Dp and colours are ARGB, like everything else here.
     */
    external fun setRoute(
        handle: Long,
        points: FloatArray,
        segmentLengths: IntArray,
        segmentColors: IntArray,
        widthDp: Float,
        casingDp: Float,
        casingColor: Int,
    )

    /** Take the route away: navigation ended, or the host cleared it. */
    external fun clearRoute(handle: Long)

    /**
     * Draw the pack-driven rail-lines network under the navigation route.
     *
     * Same bulk arrays as [setRoute] — flat lon/lat pairs, per-run point
     * counts and ARGB colours, plus the style widths — into the separate rail
     * slot, so the network and a selected route coexist. An empty push draws
     * nothing, like [clearRailLines].
     */
    external fun setRailLines(
        handle: Long,
        points: FloatArray,
        segmentLengths: IntArray,
        segmentColors: IntArray,
        widthDp: Float,
        casingDp: Float,
        casingColor: Int,
    )

    /** Take the rail network away: the transit toggle went off, or the host cleared it. */
    external fun clearRailLines(handle: Long)

    /**
     * Dim everything outside the region with id [regionId], and return whether it resolved.
     *
     * The id is the tagged OSM relation id of the admin boundary, baked onto the tapped
     * place label at build time (the tile's `region_links` table) — so the label names its
     * own outline directly and there is no point/level guess. `0` clears the mask and
     * returns 1 (resolved: there is nothing to wait for).
     *
     * Returns 1 when the mask is set (or intentionally cleared for a zero id) **and** a
     * resident tile actually carries that region, 0 when the region's tiles are not loaded
     * yet so the host retries next frame.
     */
    external fun setRegionMask(handle: Long, regionId: Long): Long

    /** Take the region mask away: the details sheet closed, or the selection moved on. */
    external fun clearRegionMask(handle: Long)

    /**
     * Pick placed labels (task 17): the last frame's placed symbol labels
     * whose screen boxes intersect the query box, in placement order.
     *
     * All four box edges are Dp from the viewport top-left. Returns one
     * `String` per hit, fields joined by `\u0001`: `layerId \u0001 name \u0001
     * kind \u0001 lon \u0001 lat \u0001 featureId`. A `\u0001`-joined string
     * (not objects) keeps the boundary allocation-free on the native side and
     * parse-trivial on the Kotlin side. Empty array when nothing was placed or
     * nothing hits.
     *
     * `kind` is the feature's own kind, not its layer's first one. `featureId` is the
     * archive's stable id, or `0` for a feature that has none.
     */
    external fun pickLabels(
        handle: Long,
        x0Dp: Float,
        y0Dp: Float,
        x1Dp: Float,
        y1Dp: Float,
    ): Array<String>

    /**
     * Pick the renderer-drawn marker under a tap via the GPU id buffer.
     *
     * [xDp]/[yDp] are Dp from the viewport top-left. Returns the tapped marker's own id — the value
     * set on it in [setMarkers] — so the caller rejoins the tap to its feature without matching on
     * position, or `0` when no marker was hit. Unlike a Compose CPU hit-test this stays correct
     * under tilt and never trails the basemap on a pan, because it reads back the same id the
     * renderer drew for the frame under the finger.
     */
    external fun pickAt(handle: Long, xDp: Float, yDp: Float): Long

    /**
     * The last frame's per-step times in nanos, in native step order (see the `Step` enum's
     * `ALL` in `timing.rs`).
     *
     * For the debug overlay's slow poll (~2–4 Hz): a copy, so the render thread never blocks
     * on it. Empty when the handle is dead or nothing has been drawn yet. Must never drive
     * the frame loop.
     */
    external fun lastFrameStepTimesNanos(handle: Long): LongArray

    /**
     * Give memory back under system pressure: evict every resident tile except the visible ones
     * (or all of them when [keepVisible] is false), wait for the GPU, and free the evicted
     * buffers, empty upload arenas and scratch high-water blocks at once.
     */
    external fun trimMemory(handle: Long, keepVisible: Boolean)

    /**
     * Destroy the renderer, wait for the GPU to go idle, and release the window.
     *
     * Must be called before the [Surface] is released, and exactly once per successful
     * [create].
     */
    external fun destroy(handle: Long)
}
